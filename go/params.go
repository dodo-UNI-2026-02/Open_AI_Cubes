package main

// Pesos de la CNN guardados en un solo arreglo []float32 con desplazamientos fijos.
// Es el mismo orden que usan Java (cubes.cnn.Params), Python, los archivos de pesos
// y el protocolo (ver docs/CNN_SPEC.md):
//
//	W1[16][3][3][3]  b1[16]       conv1
//	W2[32][16][3][3] b2[32]       conv2
//	W3[128][2048]    b3[128]      fc1 (MLP oculta)
//	W4[K][128]       b4[K]        fc2 (salida, K clases)

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strings"
)

const (
	InC    = 3
	InH    = 32
	InW    = 32
	InSize = InC * InH * InW // 3072 valores por imagen
	KS     = 3

	C1  = 16 // salida conv1 (padding 1): 16 x 32 x 32
	H1  = 32
	W1S = 32
	P1H = 16 // salida pool1: 16 x 16 x 16
	P1W = 16
	C2  = 32 // salida conv2: 32 x 16 x 16
	H2  = 16
	W2S = 16
	P2H = 8 // salida pool2: 32 x 8 x 8
	P2W = 8

	Flat = C2 * P2H * P2W // 2048
	FC1  = 128

	NW1 = C1 * InC * KS * KS
	NW2 = C2 * C1 * KS * KS
	NW3 = FC1 * Flat

	// Fixed es la cantidad de parámetros que no dependen del número de clases.
	Fixed = NW1 + C1 + NW2 + C2 + NW3 + FC1

	OW1 = 0
	OB1 = OW1 + NW1
	OW2 = OB1 + C1
	OB2 = OW2 + NW2
	OW3 = OB2 + C2
	OB3 = OW3 + NW3
	OW4 = OB3 + FC1

	weightsMagic = 0x4F435731 // "OCW1"
)

// Params contiene el vector de pesos y el número de clases K.
type Params struct {
	Classes int
	OB4     int // desplazamiento de b4 (depende de K)
	Data    []float32
}

// ParamsSize devuelve el tamaño del vector de pesos para K clases.
func ParamsSize(classes int) int {
	return Fixed + FC1*classes + classes
}

// ClassesFromSize deduce el número de clases a partir del tamaño del vector de pesos.
func ClassesFromSize(count int) (int, error) {
	k := (count - Fixed) / (FC1 + 1)
	if k <= 0 || ParamsSize(k) != count {
		return 0, fmt.Errorf("tamano de pesos invalido: %d", count)
	}
	return k, nil
}

// ParamsFromSlice usa (sin copiar) un arreglo de pesos ya decodificado.
func ParamsFromSlice(values []float32) (*Params, error) {
	k, err := ClassesFromSize(len(values))
	if err != nil {
		return nil, err
	}
	return &Params{Classes: k, OB4: OW4 + FC1*k, Data: values}, nil
}

// LoadParams lee un archivo de pesos OCW1 (big-endian).
func LoadParams(path string) (*Params, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(raw) < 8 || binary.BigEndian.Uint32(raw[0:4]) != weightsMagic {
		return nil, fmt.Errorf("no es un archivo de pesos OCW1: %s", path)
	}
	n := int(int32(binary.BigEndian.Uint32(raw[4:8])))
	if n < 0 || len(raw) < 8+4*n {
		return nil, fmt.Errorf("archivo de pesos truncado: %s", path)
	}
	return ParamsFromSlice(decodeFloats(raw[8:8+4*n], n))
}

// Save escribe los pesos en formato OCW1 (big-endian).
func (p *Params) Save(path string) error {
	if dir := filepath.Dir(path); dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	w := bufio.NewWriterSize(f, 1<<16)
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], weightsMagic)
	w.Write(b[:])
	binary.BigEndian.PutUint32(b[:], uint32(len(p.Data)))
	w.Write(b[:])
	for _, v := range p.Data {
		binary.BigEndian.PutUint32(b[:], math.Float32bits(v))
		w.Write(b[:])
	}
	if err := w.Flush(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

// decodeFloats convierte n float32 big-endian a []float32.
func decodeFloats(b []byte, n int) []float32 {
	out := make([]float32, n)
	b = b[:4*n]
	for i := range out {
		out[i] = math.Float32frombits(binary.BigEndian.Uint32(b[4*i:]))
	}
	return out
}

// ---------------------------------------------------------------------------
// Optimizador (equivale a cubes.cnn.Sgd)
// ---------------------------------------------------------------------------

// Sgd es descenso por gradiente estocástico con momentum:
//
//	v = momentum * v + g / n
//	w = w - lr * v
//
// donde g es la SUMA de gradientes de las n imágenes del lote.
type Sgd struct {
	lr       float32
	momentum float32
	velocity []float32
}

func NewSgd(lr, momentum float32) *Sgd {
	return &Sgd{lr: lr, momentum: momentum}
}

func (s *Sgd) Step(p *Params, gradSum []float32, n int) {
	w := p.Data
	if s.velocity == nil {
		s.velocity = make([]float32, len(w))
	}
	v := s.velocity[:len(w)]
	g := gradSum[:len(w)]
	inv := 1 / float32(n)
	for i := range w {
		v[i] = s.momentum*v[i] + g[i]*inv
		w[i] -= s.lr * v[i]
	}
}

// ---------------------------------------------------------------------------
// Dataset en formato binario CIFAR-10 (equivale a cubes.cnn.Dataset)
// ---------------------------------------------------------------------------

// Record es el tamaño de un registro: 1 byte de etiqueta + 3072 bytes de píxeles
// (1024 R, 1024 G, 1024 B, fila por fila).
const Record = 1 + InSize

type Dataset struct {
	Raw        []byte
	Size       int
	ClassNames []string
}

// LoadDataset lee el .bin completo y los nombres de clases de classes.txt (misma carpeta).
func LoadDataset(path string) (*Dataset, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(raw)%Record != 0 {
		return nil, fmt.Errorf("tamano no multiplo de %d: %s", Record, path)
	}
	names, err := loadClassNames(filepath.Join(filepath.Dir(path), "classes.txt"))
	if err != nil {
		return nil, err
	}
	return &Dataset{Raw: raw, Size: len(raw) / Record, ClassNames: names}, nil
}

func loadClassNames(path string) ([]string, error) {
	f, err := os.Open(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var names []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(strings.TrimPrefix(sc.Text(), "\ufeff"))
		if line != "" {
			names = append(names, line)
		}
	}
	return names, sc.Err()
}

func (d *Dataset) Label(i int) int {
	return int(d.Raw[i*Record])
}

// Pixels devuelve los 3072 bytes crudos de la imagen i.
func (d *Dataset) Pixels(i int) []byte {
	off := i*Record + 1
	return d.Raw[off : off+InSize]
}

// Image devuelve la imagen i normalizada a [-1, 1].
func (d *Dataset) Image(i int) []float32 {
	x := make([]float32, InSize)
	Normalize(d.Pixels(i), x)
	return x
}

// NumClasses: cantidad de líneas de classes.txt, o la etiqueta máxima + 1 si no existe.
func (d *Dataset) NumClasses() int {
	if len(d.ClassNames) > 0 {
		return len(d.ClassNames)
	}
	m := 0
	for i := 0; i < d.Size; i++ {
		if l := d.Label(i); l > m {
			m = l
		}
	}
	return m + 1
}

// Normalize convierte una imagen cruda (planos R, G, B) a floats en [-1, 1]:
// x = byte / 127.5 - 1 (igual que Cnn.normalize en Java).
func Normalize(raw []byte, dst []float32) {
	raw = raw[:InSize]
	dst = dst[:InSize]
	for i, b := range raw {
		dst[i] = float32(b)/127.5 - 1
	}
}
