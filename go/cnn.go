package main

// Red completa: Conv(16) -> Pool -> Conv(32) -> Pool -> Dense(128) -> Dense(K) -> Softmax.
// Equivale a cubes.cnn.Cnn en Java.
//
// Cada capa se ejecuta como un conjunto de micro chunks repartidos entre las
// goroutines del ChunkExecutor. Las capas van en orden (ForRange espera a todos los
// chunks antes de seguir), porque cada capa necesita la salida completa de la anterior.

import "math"

// Stats es el resultado de procesar un lote: suma de pérdidas y cantidad de aciertos.
type Stats struct {
	LossSum float64
	Correct int
	Count   int
}

func (s *Stats) Add(o Stats) {
	s.LossSum += o.LossSum
	s.Correct += o.Correct
	s.Count += o.Count
}

type Cnn struct {
	ex *ChunkExecutor
}

func NewCnn(ex *ChunkExecutor) *Cnn {
	return &Cnn{ex: ex}
}

// acts guarda las activaciones intermedias de un forward, necesarias para el backward.
type acts struct {
	x, a1, p1, a2, p2, h, probs [][]float32
	i1, i2                      [][]int32
}

// newMatrix reserva n filas de size floats sobre un único arreglo contiguo.
func newMatrix(n, size int) [][]float32 {
	backing := make([]float32, n*size)
	m := make([][]float32, n)
	for i := range m {
		m[i] = backing[i*size : (i+1)*size : (i+1)*size]
	}
	return m
}

func newIndexMatrix(n, size int) [][]int32 {
	backing := make([]int32, n*size)
	m := make([][]int32, n)
	for i := range m {
		m[i] = backing[i*size : (i+1)*size : (i+1)*size]
	}
	return m
}

func (c *Cnn) forward(p []float32, classes int, x [][]float32) *acts {
	n := len(x)
	ex := c.ex
	s := &acts{x: x}
	s.a1 = newMatrix(n, C1*H1*W1S)
	s.p1 = newMatrix(n, C1*P1H*P1W)
	s.i1 = newIndexMatrix(n, C1*P1H*P1W)
	s.a2 = newMatrix(n, C2*H2*W2S)
	s.p2 = newMatrix(n, Flat)
	s.i2 = newIndexMatrix(n, Flat)
	s.h = newMatrix(n, FC1)
	logits := newMatrix(n, classes)

	ex.ForRange(C1, func(a, b int) { convForward(x, InC, InH, InW, p, OW1, OB1, s.a1, a, b) })
	ex.ForRange(C1, func(a, b int) { poolForward(s.a1, H1, W1S, s.p1, s.i1, a, b) })
	ex.ForRange(C2, func(a, b int) { convForward(s.p1, C1, H2, W2S, p, OW2, OB2, s.a2, a, b) })
	ex.ForRange(C2, func(a, b int) { poolForward(s.a2, H2, W2S, s.p2, s.i2, a, b) })
	ex.ForRange(FC1, func(a, b int) { mlpForward(s.p2, Flat, p, OW3, OB3, s.h, true, a, b) })
	oB4 := OW4 + FC1*classes
	ex.ForRange(classes, func(a, b int) { mlpForward(s.h, FC1, p, OW4, oB4, logits, false, a, b) })

	s.probs = make([][]float32, n)
	for i := range logits {
		s.probs[i] = softmax(logits[i])
	}
	return s
}

func softmax(z []float32) []float32 {
	mx := z[0]
	for _, v := range z {
		if v > mx {
			mx = v
		}
	}
	var sum float64
	out := make([]float32, len(z))
	for i, v := range z {
		out[i] = float32(math.Exp(float64(v - mx)))
		sum += float64(out[i])
	}
	for i := range out {
		out[i] = float32(float64(out[i]) / sum)
	}
	return out
}

// Argmax devuelve el índice del mayor valor (empate -> índice menor).
func Argmax(v []float32) int {
	best := 0
	for i := 1; i < len(v); i++ {
		if v[i] > v[best] {
			best = i
		}
	}
	return best
}

// Predict es solo inferencia: devuelve las probabilidades por clase de cada imagen.
func (c *Cnn) Predict(params *Params, x [][]float32) [][]float32 {
	return c.forward(params.Data, params.Classes, x).probs
}

// ForwardBackward hace forward + backward sobre un lote. Los gradientes (SUMA sobre
// las imágenes, no promedio) se acumulan en grad, que debe tener el tamaño de params.Data.
func (c *Cnn) ForwardBackward(params *Params, x [][]float32, labels []int, grad []float32) Stats {
	p := params.Data
	classes := params.Classes
	n := len(x)
	ex := c.ex
	s := c.forward(p, classes, x)

	st := Stats{Count: n}
	dL := newMatrix(n, classes)
	for i := 0; i < n; i++ {
		pr := s.probs[i]
		pl := pr[labels[i]]
		if pl < 1e-12 {
			pl = 1e-12
		}
		st.LossSum += -math.Log(float64(pl))
		if Argmax(pr) == labels[i] {
			st.Correct++
		}
		for k := 0; k < classes; k++ {
			if k == labels[i] {
				dL[i][k] = pr[k] - 1
			} else {
				dL[i][k] = pr[k]
			}
		}
	}

	oB4 := OW4 + FC1*classes
	// MLP de salida y capa oculta
	ex.ForRange(classes, func(a, b int) { mlpBackwardWeights(s.h, FC1, dL, grad, OW4, oB4, a, b) })
	dH := newMatrix(n, FC1)
	ex.ForRange(FC1, func(a, b int) { mlpBackwardInput(dL, classes, p, OW4, FC1, dH, s.h, a, b) })
	ex.ForRange(FC1, func(a, b int) { mlpBackwardWeights(s.p2, Flat, dH, grad, OW3, OB3, a, b) })
	dP2 := newMatrix(n, Flat)
	ex.ForRange(Flat, func(a, b int) { mlpBackwardInput(dH, FC1, p, OW3, Flat, dP2, nil, a, b) })
	// Bloque convolucional 2
	dZ2 := newMatrix(n, C2*H2*W2S)
	ex.ForRange(C2, func(a, b int) { poolBackward(dP2, s.i2, s.a2, H2, W2S, dZ2, a, b) })
	ex.ForRange(C2, func(a, b int) { convBackwardWeights(s.p1, C1, H2, W2S, dZ2, grad, OW2, OB2, a, b) })
	dP1 := newMatrix(n, C1*P1H*P1W)
	ex.ForRange(C1, func(a, b int) { convBackwardInput(dZ2, C2, p, OW2, C1, H2, W2S, dP1, a, b) })
	// Bloque convolucional 1 (no hace falta el gradiente de la imagen de entrada)
	dZ1 := newMatrix(n, C1*H1*W1S)
	ex.ForRange(C1, func(a, b int) { poolBackward(dP1, s.i1, s.a1, H1, W1S, dZ1, a, b) })
	ex.ForRange(C1, func(a, b int) { convBackwardWeights(x, InC, InH, InW, dZ1, grad, OW1, OB1, a, b) })
	return st
}
