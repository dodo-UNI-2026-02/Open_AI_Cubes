package main

// Protocolo OC/1 (ver docs/PROTOCOLO.md), lado del nodo.
//
// Trama: cabecera de 12 bytes big-endian + payload
//
//	'O' 'C' versión(1) tipo(1) | id petición (i32) | longitud (i32) | payload
//
// Tipos del payload: u8, i32, f32, f64, str (u16 largo + UTF-8), f32[] (i32 cantidad +
// floats), pixeles (n x 3072 bytes CIFAR). Solo se usa el paquete net de la biblioteca
// estándar: nada de HTTP, WebSocket ni middleware.

import (
	"encoding/binary"
	"fmt"
	"io"
	"math"
)

const (
	protoVersion = 1
	headerSize   = 12
	maxPayload   = 64 << 20 // 64 MiB

	MsgRegister    = 0x01 // nodo -> srv: str lenguaje, i32 nucleos, i32 hilos, str hostname
	MsgRegisterOK  = 0x02 // srv -> nodo: i32 nodeId
	MsgHeartbeat   = 0x03 // nodo -> srv: i32 tareas pendientes (cada 2 s, id 0)
	MsgBye         = 0x04 // ambos: vacio; luego se cierra la conexion
	MsgSetWeights  = 0x10 // srv -> nodo: u8 slot, i32 version, f32[] pesos
	MsgTrainTask   = 0x11 // srv -> nodo: u8 slot, i32 version, i32 n, n bytes etiquetas, pixeles
	MsgGradients   = 0x12 // nodo -> srv: i32 version, i32 n, f64 perdidas, i32 aciertos, i32 ms, f32[] gradientes
	MsgInferTask   = 0x20 // srv -> nodo: u8 slot, i32 version, i32 n, pixeles
	MsgInferResult = 0x21 // nodo -> srv: i32 n, i32 K, n*K f32 probabilidades, i32 ms
	MsgError       = 0x7F // ambos: str mensaje (con el id de la peticion que fallo)
)

// msgName devuelve el nombre de un tipo de mensaje (para los mensajes de consola).
func msgName(t byte) string {
	switch t {
	case MsgRegister:
		return "REGISTER"
	case MsgRegisterOK:
		return "REGISTER_OK"
	case MsgHeartbeat:
		return "HEARTBEAT"
	case MsgBye:
		return "BYE"
	case MsgSetWeights:
		return "SET_WEIGHTS"
	case MsgTrainTask:
		return "TRAIN_TASK"
	case MsgGradients:
		return "GRADIENTS"
	case MsgInferTask:
		return "INFER_TASK"
	case MsgInferResult:
		return "INFER_RESULT"
	case MsgError:
		return "ERROR"
	}
	return fmt.Sprintf("0x%02X", t)
}

// Frame es una trama recibida.
type Frame struct {
	Type    byte
	ID      int32
	Payload []byte
}

// readFrame lee una trama completa (cabecera + payload) y valida la cabecera.
func readFrame(r io.Reader) (Frame, error) {
	var h [headerSize]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return Frame{}, err
	}
	if h[0] != 'O' || h[1] != 'C' {
		return Frame{}, fmt.Errorf("cabecera invalida (no empieza con 'OC')")
	}
	if h[2] != protoVersion {
		return Frame{}, fmt.Errorf("version de protocolo no soportada: %d", h[2])
	}
	f := Frame{Type: h[3], ID: int32(binary.BigEndian.Uint32(h[4:8]))}
	length := int32(binary.BigEndian.Uint32(h[8:12]))
	if length < 0 || length > maxPayload {
		return Frame{}, fmt.Errorf("longitud de payload invalida: %d", length)
	}
	f.Payload = make([]byte, length)
	if _, err := io.ReadFull(r, f.Payload); err != nil {
		return Frame{}, err
	}
	return f, nil
}

// ---------------------------------------------------------------------------
// Construcción de tramas
// ---------------------------------------------------------------------------

// frameBuilder arma cabecera + payload en un solo []byte, así cada trama se envía con
// una única escritura al socket.
type frameBuilder struct {
	buf []byte
}

func newFrame(msgType byte, id int32, payloadHint int) *frameBuilder {
	b := make([]byte, headerSize, headerSize+payloadHint)
	b[0], b[1], b[2], b[3] = 'O', 'C', protoVersion, msgType
	binary.BigEndian.PutUint32(b[4:8], uint32(id))
	return &frameBuilder{buf: b}
}

// grow agrega n bytes al final y devuelve el trozo nuevo para escribirlo.
func (f *frameBuilder) grow(n int) []byte {
	l := len(f.buf)
	if cap(f.buf)-l < n {
		nb := make([]byte, l, 2*cap(f.buf)+n)
		copy(nb, f.buf)
		f.buf = nb
	}
	f.buf = f.buf[:l+n]
	return f.buf[l : l+n]
}

func (f *frameBuilder) u8(v byte) { f.grow(1)[0] = v }

func (f *frameBuilder) i32(v int32) { binary.BigEndian.PutUint32(f.grow(4), uint32(v)) }

func (f *frameBuilder) f32(v float32) { binary.BigEndian.PutUint32(f.grow(4), math.Float32bits(v)) }

func (f *frameBuilder) f64(v float64) { binary.BigEndian.PutUint64(f.grow(8), math.Float64bits(v)) }

func (f *frameBuilder) str(s string) {
	if len(s) > 0xFFFF {
		s = s[:0xFFFF]
	}
	binary.BigEndian.PutUint16(f.grow(2), uint16(len(s)))
	copy(f.grow(len(s)), s)
}

// f32n escribe los floats sin cantidad delante (por ejemplo n*K probabilidades).
func (f *frameBuilder) f32n(v []float32) {
	b := f.grow(4 * len(v))
	for i, x := range v {
		binary.BigEndian.PutUint32(b[4*i:], math.Float32bits(x))
	}
}

// f32s escribe un f32[]: i32 cantidad + floats.
func (f *frameBuilder) f32s(v []float32) {
	f.i32(int32(len(v)))
	f.f32n(v)
}

// bytes completa la longitud de la cabecera y devuelve la trama lista para enviar.
func (f *frameBuilder) bytes() []byte {
	binary.BigEndian.PutUint32(f.buf[8:12], uint32(len(f.buf)-headerSize))
	return f.buf
}

// ---------------------------------------------------------------------------
// Lectura de payloads
// ---------------------------------------------------------------------------

// payloadReader lee valores big-endian de un payload. Si faltan bytes, guarda el
// primer error en err y devuelve ceros (se comprueba una vez al final).
type payloadReader struct {
	buf []byte
	off int
	err error
}

func (r *payloadReader) take(n int) []byte {
	if r.err != nil {
		return nil
	}
	if n < 0 || len(r.buf)-r.off < n {
		r.err = fmt.Errorf("payload truncado (faltan %d bytes)", n-(len(r.buf)-r.off))
		return nil
	}
	b := r.buf[r.off : r.off+n]
	r.off += n
	return b
}

func (r *payloadReader) remaining() int { return len(r.buf) - r.off }

func (r *payloadReader) u8() byte {
	if b := r.take(1); b != nil {
		return b[0]
	}
	return 0
}

func (r *payloadReader) i32() int32 {
	if b := r.take(4); b != nil {
		return int32(binary.BigEndian.Uint32(b))
	}
	return 0
}

func (r *payloadReader) f64() float64 {
	if b := r.take(8); b != nil {
		return math.Float64frombits(binary.BigEndian.Uint64(b))
	}
	return 0
}

func (r *payloadReader) str() string {
	b := r.take(2)
	if b == nil {
		return ""
	}
	return string(r.take(int(binary.BigEndian.Uint16(b))))
}

// f32n lee n floats sin cantidad delante.
func (r *payloadReader) f32n(n int) []float32 {
	if n < 0 || n > r.remaining()/4 {
		if r.err == nil {
			r.err = fmt.Errorf("cantidad de floats invalida: %d", n)
		}
		return nil
	}
	return decodeFloats(r.take(4*n), n)
}

// f32s lee un f32[]: i32 cantidad + floats.
func (r *payloadReader) f32s() []float32 {
	n := int(r.i32())
	if r.err != nil {
		return nil
	}
	return r.f32n(n)
}

// end comprueba que no sobren bytes y devuelve el primer error encontrado.
func (r *payloadReader) end() error {
	if r.err == nil && r.off != len(r.buf) {
		r.err = fmt.Errorf("sobran %d bytes en el payload", len(r.buf)-r.off)
	}
	return r.err
}
