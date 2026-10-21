package main

// Nodo de cómputo en red (modo por defecto).
//
// El nodo abre la conexión TCP hacia el servidor central (puerto 6000), se registra y
// queda atendiendo tareas:
//
//   - goroutine lectora: lee tramas del socket y las pone en una cola (canal) en orden.
//   - goroutine trabajadora: procesa la cola estrictamente en el orden de llegada
//     (SET_WEIGHTS siempre llega antes que la tarea que lo usa, TCP mantiene el orden).
//     Cada tarea usa la CNN con H goroutines por capa (ChunkExecutor).
//   - goroutine de latidos: envía HEARTBEAT cada 2 s aunque haya una tarea en curso.
//
// Las escrituras al socket (resultados, latidos, BYE) se serializan con un mutex para
// que dos tramas nunca se mezclen. Si se cae la conexión, se reintenta cada pocos
// segundos. Ctrl+C envía BYE antes de salir.

import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	heartbeatEvery = 2 * time.Second
	writeTimeout   = 30 * time.Second
	dialTimeout    = 5 * time.Second
)

var errServerBye = errors.New("el servidor envio BYE")

type nodeConfig struct {
	server  string
	port    int
	threads int
	retry   time.Duration
	quiet   bool
}

// weightSlot guarda los pesos de un slot (0 = servicio, 1 = entrenamiento) con su versión.
type weightSlot struct {
	version int32
	params  *Params
}

// session es una conexión con el servidor. Se crea una nueva en cada reconexión.
type session struct {
	conn    net.Conn
	wmu     sync.Mutex // serializa las escrituras al socket
	pending int32      // tareas en cola o en curso (atómico), se informa en HEARTBEAT
	closed  int32      // 1 cuando la conexión terminó (atómico)
	cnn     *Cnn
	quiet   bool
	slots   map[byte]*weightSlot // solo lo usa la goroutine trabajadora
}

func runNode(cfg nodeConfig) {
	addr := net.JoinHostPort(cfg.server, strconv.Itoa(cfg.port))
	hostname, err := os.Hostname()
	if err != nil {
		hostname = "desconocido"
	}
	cnn := NewCnn(NewChunkExecutor(cfg.threads))
	fmt.Printf("Nodo Go: servidor=%s hilos=%d nucleos=%d host=%s\n", addr, cfg.threads, runtime.NumCPU(), hostname)

	// Ctrl+C (o SIGTERM): enviar BYE si hay conexión y salir.
	var curMu sync.Mutex
	var cur *session
	var exiting int32 // 1 cuando se está saliendo por Ctrl+C: el bucle no debe anunciar un reintento
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigs
		curMu.Lock()
		s := cur
		curMu.Unlock()
		atomic.StoreInt32(&exiting, 1)
		if s != nil {
			fmt.Println("\nCtrl+C: enviando BYE al servidor")
			sent := make(chan struct{})
			go func() {
				s.send(newFrame(MsgBye, 0, 0).bytes())
				close(sent)
			}()
			select {
			case <-sent:
			case <-time.After(2 * time.Second):
			}
			s.conn.Close()
		} else {
			fmt.Println("\nCtrl+C: saliendo")
		}
		os.Exit(0)
	}()

	for {
		conn, err := net.DialTimeout("tcp", addr, dialTimeout)
		if err != nil {
			fmt.Printf("No se pudo conectar a %s (%v); reintento en %v\n", addr, err, cfg.retry)
			time.Sleep(cfg.retry)
			continue
		}
		fmt.Printf("Conectado a %s\n", addr)
		s := &session{conn: conn, cnn: cnn, quiet: cfg.quiet, slots: make(map[byte]*weightSlot)}
		curMu.Lock()
		cur = s
		curMu.Unlock()
		err = s.run(hostname, cfg.threads)
		curMu.Lock()
		cur = nil
		curMu.Unlock()
		if atomic.LoadInt32(&exiting) == 1 {
			select {} // la goroutine de la señal termina el proceso
		}
		fmt.Printf("Conexion terminada: %v; reintento en %v\n", err, cfg.retry)
		time.Sleep(cfg.retry)
	}
}

// send escribe una trama completa al socket, protegida por el mutex de escritura.
func (s *session) send(frame []byte) error {
	s.wmu.Lock()
	defer s.wmu.Unlock()
	s.conn.SetWriteDeadline(time.Now().Add(writeTimeout))
	_, err := s.conn.Write(frame)
	return err
}

func (s *session) sendError(id int32, msg string) {
	f := newFrame(MsgError, id, 2+len(msg))
	f.str(msg)
	s.send(f.bytes())
}

func (s *session) logf(format string, args ...interface{}) {
	if !s.quiet {
		fmt.Printf(format, args...)
	}
}

// run atiende una conexión hasta que se corta. Devuelve el motivo.
func (s *session) run(hostname string, threads int) error {
	defer s.conn.Close()

	reg := newFrame(MsgRegister, 1, 64)
	reg.str("go")
	reg.i32(int32(runtime.NumCPU()))
	reg.i32(int32(threads))
	reg.str(hostname)
	if err := s.send(reg.bytes()); err != nil {
		return err
	}

	queue := make(chan Frame, 16)
	stopHeartbeat := make(chan struct{})
	workerDone := make(chan struct{})
	go s.heartbeatLoop(stopHeartbeat)
	go s.worker(queue, workerDone)

	// Goroutine lectora (la actual): solo lee y encola, en orden.
	br := bufio.NewReaderSize(s.conn, 1<<16)
	var err error
	for {
		var fr Frame
		fr, err = readFrame(br)
		if err != nil {
			break
		}
		if fr.Type == MsgBye {
			err = errServerBye
			break
		}
		if isTask(fr.Type) {
			atomic.AddInt32(&s.pending, 1)
		}
		queue <- fr
	}

	atomic.StoreInt32(&s.closed, 1)
	s.conn.Close() // desbloquea cualquier escritura en curso
	close(queue)
	<-workerDone
	close(stopHeartbeat)
	return err
}

func isTask(t byte) bool {
	return t == MsgTrainTask || t == MsgInferTask
}

// heartbeatLoop envía HEARTBEAT (id 0, i32 tareas pendientes) cada 2 s.
func (s *session) heartbeatLoop(stop <-chan struct{}) {
	t := time.NewTicker(heartbeatEvery)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			f := newFrame(MsgHeartbeat, 0, 4)
			f.i32(atomic.LoadInt32(&s.pending))
			if err := s.send(f.bytes()); err != nil {
				s.conn.Close() // la lectora verá el error y terminará la sesión
				return
			}
		}
	}
}

// worker procesa los mensajes de la cola uno por uno, en orden de llegada.
func (s *session) worker(queue <-chan Frame, done chan<- struct{}) {
	defer close(done)
	for fr := range queue {
		if atomic.LoadInt32(&s.closed) == 1 {
			// La conexión ya se cortó: no tiene sentido calcular, se descarta.
			if isTask(fr.Type) {
				atomic.AddInt32(&s.pending, -1)
			}
			continue
		}
		s.handle(fr)
	}
}

func (s *session) handle(fr Frame) {
	defer func() {
		if isTask(fr.Type) {
			atomic.AddInt32(&s.pending, -1)
		}
		if r := recover(); r != nil {
			fmt.Printf("Error interno procesando %s id=%d: %v\n", msgName(fr.Type), fr.ID, r)
			s.sendError(fr.ID, fmt.Sprintf("error interno: %v", r))
		}
	}()
	switch fr.Type {
	case MsgRegisterOK:
		r := &payloadReader{buf: fr.Payload}
		id := r.i32()
		if err := r.end(); err != nil {
			fmt.Printf("REGISTER_OK invalido: %v\n", err)
			return
		}
		fmt.Printf("Registrado en el servidor como nodo %d\n", id)
	case MsgSetWeights:
		s.handleSetWeights(fr)
	case MsgTrainTask:
		s.handleTrain(fr)
	case MsgInferTask:
		s.handleInfer(fr)
	case MsgError:
		r := &payloadReader{buf: fr.Payload}
		fmt.Printf("ERROR del servidor (id %d): %s\n", fr.ID, r.str())
	case MsgHeartbeat:
		// El servidor no debería enviarlos; se ignoran.
	default:
		fmt.Printf("Mensaje desconocido %s id=%d (%d bytes)\n", msgName(fr.Type), fr.ID, len(fr.Payload))
		s.sendError(fr.ID, "tipo de mensaje desconocido: "+msgName(fr.Type))
	}
}

// badPayload informa un payload mal formado.
func (s *session) badPayload(fr Frame, err error) {
	fmt.Printf("%s id=%d mal formado: %v\n", msgName(fr.Type), fr.ID, err)
	s.sendError(fr.ID, fmt.Sprintf("%s mal formado: %v", msgName(fr.Type), err))
}

// SET_WEIGHTS: u8 slot, i32 versión, f32[] pesos (sin respuesta).
func (s *session) handleSetWeights(fr Frame) {
	r := &payloadReader{buf: fr.Payload}
	slot := r.u8()
	version := r.i32()
	values := r.f32s()
	if err := r.end(); err != nil {
		s.badPayload(fr, err)
		return
	}
	p, err := ParamsFromSlice(values)
	if err != nil {
		s.badPayload(fr, err)
		return
	}
	s.slots[slot] = &weightSlot{version: version, params: p}
	s.logf("SET_WEIGHTS slot=%d version=%d (%d pesos, K=%d)\n", slot, version, len(values), p.Classes)
}

// checkSlot devuelve los pesos del slot si la versión coincide; si no, responde ERROR "version".
func (s *session) checkSlot(fr Frame, slot byte, version int32) *weightSlot {
	ws := s.slots[slot]
	if ws == nil || ws.version != version {
		have := "ninguna"
		if ws != nil {
			have = strconv.Itoa(int(ws.version))
		}
		fmt.Printf("%s id=%d: version %d pedida en slot %d, guardada %s -> ERROR version\n",
			msgName(fr.Type), fr.ID, version, slot, have)
		s.sendError(fr.ID, "version")
		return nil
	}
	return ws
}

// readImages lee n imágenes (pixeles CIFAR) del payload y las normaliza.
func readImages(r *payloadReader, n int) [][]float32 {
	raw := r.take(n * InSize)
	if raw == nil {
		return nil
	}
	x := newMatrix(n, InSize)
	for i := range x {
		Normalize(raw[i*InSize:(i+1)*InSize], x[i])
	}
	return x
}

// TRAIN_TASK: u8 slot, i32 versión, i32 n, n bytes etiquetas, pixeles.
// Respuesta GRADIENTS: i32 versión, i32 n, f64 suma pérdidas, i32 aciertos, i32 ms, f32[] gradientes.
func (s *session) handleTrain(fr Frame) {
	t0 := time.Now()
	r := &payloadReader{buf: fr.Payload}
	slot := r.u8()
	version := r.i32()
	n := int(r.i32())
	if r.err == nil && (n < 0 || n > r.remaining()/(1+InSize)) {
		s.badPayload(fr, fmt.Errorf("n=%d no coincide con %d bytes de datos", n, r.remaining()))
		return
	}
	labels := r.take(n)
	x := readImages(r, n)
	if err := r.end(); err != nil {
		s.badPayload(fr, err)
		return
	}
	ws := s.checkSlot(fr, slot, version)
	if ws == nil {
		return
	}
	y := make([]int, n)
	for i, l := range labels {
		if int(l) >= ws.params.Classes {
			s.badPayload(fr, fmt.Errorf("etiqueta %d fuera de rango (K=%d)", l, ws.params.Classes))
			return
		}
		y[i] = int(l)
	}

	grad := make([]float32, len(ws.params.Data))
	st := s.cnn.ForwardBackward(ws.params, x, y, grad)
	ms := int32(time.Since(t0) / time.Millisecond)

	out := newFrame(MsgGradients, fr.ID, 28+4*len(grad))
	out.i32(version)
	out.i32(int32(n))
	out.f64(st.LossSum)
	out.i32(int32(st.Correct))
	out.i32(ms)
	out.f32s(grad)
	if err := s.send(out.bytes()); err != nil {
		fmt.Printf("No se pudo enviar GRADIENTS id=%d: %v\n", fr.ID, err)
		return
	}
	meanLoss := 0.0
	if n > 0 {
		meanLoss = st.LossSum / float64(n)
	}
	s.logf("TRAIN_TASK id=%d slot=%d v=%d n=%d: perdida media %.4f, aciertos %d, %d ms\n",
		fr.ID, slot, version, n, meanLoss, st.Correct, ms)
}

// INFER_TASK: u8 slot, i32 versión, i32 n, pixeles.
// Respuesta INFER_RESULT: i32 n, i32 K, n*K f32 probabilidades, i32 ms.
func (s *session) handleInfer(fr Frame) {
	t0 := time.Now()
	r := &payloadReader{buf: fr.Payload}
	slot := r.u8()
	version := r.i32()
	n := int(r.i32())
	if r.err == nil && (n < 0 || n > r.remaining()/InSize) {
		s.badPayload(fr, fmt.Errorf("n=%d no coincide con %d bytes de pixeles", n, r.remaining()))
		return
	}
	x := readImages(r, n)
	if err := r.end(); err != nil {
		s.badPayload(fr, err)
		return
	}
	ws := s.checkSlot(fr, slot, version)
	if ws == nil {
		return
	}

	probs := s.cnn.Predict(ws.params, x)
	ms := int32(time.Since(t0) / time.Millisecond)

	k := ws.params.Classes
	out := newFrame(MsgInferResult, fr.ID, 12+4*n*k)
	out.i32(int32(n))
	out.i32(int32(k))
	for _, p := range probs {
		out.f32n(p)
	}
	out.i32(ms)
	if err := s.send(out.bytes()); err != nil {
		fmt.Printf("No se pudo enviar INFER_RESULT id=%d: %v\n", fr.ID, err)
		return
	}
	s.logf("INFER_TASK id=%d slot=%d v=%d n=%d: %d ms\n", fr.ID, slot, version, n, ms)
}
