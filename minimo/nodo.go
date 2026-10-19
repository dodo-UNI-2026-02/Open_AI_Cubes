// Nodo de cómputo en Go para Open AI Cubes. Misma CNN y mismo protocolo que el nodo Java;
// cada capa se reparte entre goroutines (micro chunks) y sync.WaitGroup hace de barrera.
// Uso: go run nodo.go [ip_servidor] [hilos]
package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"runtime"
	"strconv"
	"sync"
)

const (
	C1, C2, FC1, FLAT, IN = 16, 32, 128, 32 * 64, 3072
	W1                    = 0
	B1                    = W1 + C1*27
	W2                    = B1 + C1
	B2                    = W2 + C2*C1*9
	W3                    = B2 + C2
	B3                    = W3 + FC1*FLAT
	W4                    = B3 + FC1
)

var hilos = runtime.NumCPU()

// paralelo divide [0, n) en un rango por goroutine y espera a que todas terminen.
func paralelo(n int, f func(a, b int)) {
	var wg sync.WaitGroup
	for t := 0; t < hilos; t++ {
		a, b := n*t/hilos, n*(t+1)/hilos
		if a < b {
			wg.Add(1)
			go func(a, b int) { defer wg.Done(); f(a, b) }(a, b)
		}
	}
	wg.Wait()
}

func matriz(n, m int) [][]float32 {
	r := make([][]float32, n)
	for i := range r {
		r[i] = make([]float32, m)
	}
	return r
}

// Convolución 3x3 con padding 1 y ReLU, filtros f0..f1.
func conv(in [][]float32, inC, s int, p []float32, ow, ob int, out [][]float32, f0, f1 int) {
	hw := s * s
	for n := range in {
		for f := f0; f < f1; f++ {
			for y := 0; y < s; y++ {
				for x := 0; x < s; x++ {
					sum := p[ob+f]
					for c := 0; c < inC; c++ {
						for ky := 0; ky < 3; ky++ {
							yy := y + ky - 1
							if yy < 0 || yy >= s {
								continue
							}
							for kx := 0; kx < 3; kx++ {
								if xx := x + kx - 1; xx >= 0 && xx < s {
									sum += p[ow+((f*inC+c)*3+ky)*3+kx] * in[n][c*hw+yy*s+xx]
								}
							}
						}
					}
					out[n][f*hw+y*s+x] = float32(math.Max(0, float64(sum)))
				}
			}
		}
	}
}

// Gradiente de los pesos de los filtros f0..f1.
func convPesos(in [][]float32, inC, s int, dz [][]float32, g []float32, ow, ob, f0, f1 int) {
	hw := s * s
	for n := range in {
		for f := f0; f < f1; f++ {
			for y := 0; y < s; y++ {
				for x := 0; x < s; x++ {
					d := dz[n][f*hw+y*s+x]
					if d == 0 {
						continue
					}
					g[ob+f] += d
					for c := 0; c < inC; c++ {
						for ky := 0; ky < 3; ky++ {
							yy := y + ky - 1
							if yy < 0 || yy >= s {
								continue
							}
							for kx := 0; kx < 3; kx++ {
								if xx := x + kx - 1; xx >= 0 && xx < s {
									g[ow+((f*inC+c)*3+ky)*3+kx] += d * in[n][c*hw+yy*s+xx]
								}
							}
						}
					}
				}
			}
		}
	}
}

// Gradiente de la entrada de una convolución, canales c0..c1.
func convEntrada(dz [][]float32, outC, inC, s int, p []float32, ow int, din [][]float32, c0, c1 int) {
	hw := s * s
	for n := range dz {
		for c := c0; c < c1; c++ {
			for f := 0; f < outC; f++ {
				for y := 0; y < s; y++ {
					for x := 0; x < s; x++ {
						d := dz[n][f*hw+y*s+x]
						if d == 0 {
							continue
						}
						for ky := 0; ky < 3; ky++ {
							yy := y + ky - 1
							if yy < 0 || yy >= s {
								continue
							}
							for kx := 0; kx < 3; kx++ {
								if xx := x + kx - 1; xx >= 0 && xx < s {
									din[n][c*hw+yy*s+xx] += p[ow+((f*inC+c)*3+ky)*3+kx] * d
								}
							}
						}
					}
				}
			}
		}
	}
}

// Max pooling 2x2, canales c0..c1.
func pool(in [][]float32, s int, out [][]float32, idx [][]int, c0, c1 int) {
	h := s / 2
	for n := range in {
		for c := c0; c < c1; c++ {
			for y := 0; y < h; y++ {
				for x := 0; x < h; x++ {
					base := c*s*s + 2*y*s + 2*x
					best := base
					for _, k := range []int{base + 1, base + s, base + s + 1} {
						if in[n][k] > in[n][best] {
							best = k
						}
					}
					o := c*h*h + y*h + x
					out[n][o], idx[n][o] = in[n][best], best
				}
			}
		}
	}
}

func poolAtras(dout [][]float32, idx [][]int, act, dz [][]float32, per, c0, c1 int) {
	for n := range dout {
		for k := c0 * per; k < c1*per; k++ {
			if i := idx[n][k]; act[n][i] > 0 {
				dz[n][i] = dout[n][k]
			}
		}
	}
}

// Capa densa (MLP), neuronas j0..j1.
func densa(in [][]float32, inN int, p []float32, ow, ob int, out [][]float32, relu bool, j0, j1 int) {
	for n := range in {
		for j := j0; j < j1; j++ {
			s := p[ob+j]
			for i := 0; i < inN; i++ {
				s += p[ow+j*inN+i] * in[n][i]
			}
			if relu && s < 0 {
				s = 0
			}
			out[n][j] = s
		}
	}
}

func densaPesos(in [][]float32, inN int, dz [][]float32, g []float32, ow, ob, j0, j1 int) {
	for n := range in {
		for j := j0; j < j1; j++ {
			d := dz[n][j]
			if d == 0 {
				continue
			}
			g[ob+j] += d
			for i := 0; i < inN; i++ {
				g[ow+j*inN+i] += d * in[n][i]
			}
		}
	}
}

func densaEntrada(dz [][]float32, outN, inN int, p []float32, ow int, din, act [][]float32, i0, i1 int) {
	for n := range dz {
		for i := i0; i < i1; i++ {
			var s float32
			for j := 0; j < outN; j++ {
				s += dz[n][j] * p[ow+j*inN+i]
			}
			if act != nil && act[n][i] <= 0 {
				s = 0
			}
			din[n][i] = s
		}
	}
}

type activaciones struct {
	x, a1, p1, a2, p2, h, probs [][]float32
	i1, i2                      [][]int
}

func adelante(p []float32, x [][]float32) *activaciones {
	n, k := len(x), (len(p)-W4)/(FC1+1)
	b4 := W4 + FC1*k
	a := &activaciones{x: x, a1: matriz(n, C1*1024), p1: matriz(n, C1*256), a2: matriz(n, C2*256), p2: matriz(n, FLAT), h: matriz(n, FC1)}
	a.i1, a.i2 = make([][]int, n), make([][]int, n)
	for i := 0; i < n; i++ {
		a.i1[i], a.i2[i] = make([]int, C1*256), make([]int, FLAT)
	}
	z := matriz(n, k)
	paralelo(C1, func(f0, f1 int) { conv(x, 3, 32, p, W1, B1, a.a1, f0, f1) })
	paralelo(C1, func(c0, c1 int) { pool(a.a1, 32, a.p1, a.i1, c0, c1) })
	paralelo(C2, func(f0, f1 int) { conv(a.p1, C1, 16, p, W2, B2, a.a2, f0, f1) })
	paralelo(C2, func(c0, c1 int) { pool(a.a2, 16, a.p2, a.i2, c0, c1) })
	paralelo(FC1, func(j0, j1 int) { densa(a.p2, FLAT, p, W3, B3, a.h, true, j0, j1) })
	paralelo(k, func(j0, j1 int) { densa(a.h, FC1, p, W4, b4, z, false, j0, j1) })
	a.probs = make([][]float32, n)
	for i := range z {
		a.probs[i] = softmax(z[i])
	}
	return a
}

func softmax(z []float32) []float32 {
	max := z[0]
	for _, v := range z {
		if v > max {
			max = v
		}
	}
	out, sum := make([]float32, len(z)), 0.0
	for i, v := range z {
		out[i] = float32(math.Exp(float64(v - max)))
		sum += float64(out[i])
	}
	for i := range out {
		out[i] = float32(float64(out[i]) / sum)
	}
	return out
}

// entrenarLote suma en g los gradientes del lote y devuelve la pérdida total y los aciertos.
func entrenarLote(p []float32, x [][]float32, y []int, g []float32) (float64, int32) {
	n, k := len(x), (len(p)-W4)/(FC1+1)
	b4 := W4 + FC1*k
	a := adelante(p, x)
	perdida, aciertos := 0.0, int32(0)
	dz := matriz(n, k)
	for i := 0; i < n; i++ {
		perdida -= math.Log(math.Max(float64(a.probs[i][y[i]]), 1e-12))
		best := 0
		for c := 0; c < k; c++ {
			if a.probs[i][c] > a.probs[i][best] {
				best = c
			}
			dz[i][c] = a.probs[i][c]
			if c == y[i] {
				dz[i][c]--
			}
		}
		if best == y[i] {
			aciertos++
		}
	}
	dh, dp2, dz2, dp1, dz1 := matriz(n, FC1), matriz(n, FLAT), matriz(n, C2*256), matriz(n, C1*256), matriz(n, C1*1024)
	paralelo(k, func(j0, j1 int) { densaPesos(a.h, FC1, dz, g, W4, b4, j0, j1) })
	paralelo(FC1, func(i0, i1 int) { densaEntrada(dz, k, FC1, p, W4, dh, a.h, i0, i1) })
	paralelo(FC1, func(j0, j1 int) { densaPesos(a.p2, FLAT, dh, g, W3, B3, j0, j1) })
	paralelo(FLAT, func(i0, i1 int) { densaEntrada(dh, FC1, FLAT, p, W3, dp2, nil, i0, i1) })
	paralelo(C2, func(c0, c1 int) { poolAtras(dp2, a.i2, a.a2, dz2, 64, c0, c1) })
	paralelo(C2, func(f0, f1 int) { convPesos(a.p1, C1, 16, dz2, g, W2, B2, f0, f1) })
	paralelo(C1, func(c0, c1 int) { convEntrada(dz2, C2, C1, 16, p, W2, dp1, c0, c1) })
	paralelo(C1, func(c0, c1 int) { poolAtras(dp1, a.i1, a.a1, dz1, 256, c0, c1) })
	paralelo(C1, func(f0, f1 int) { convPesos(a.x, 3, 32, dz1, g, W1, B1, f0, f1) })
	return perdida, aciertos
}

// ---------- Protocolo con el servidor (big-endian, igual que DataInput/DataOutput de Java) ----------

func leer(r io.Reader, v interface{}) {
	if err := binary.Read(r, binary.BigEndian, v); err != nil {
		fmt.Println("Conexión cerrada:", err)
		os.Exit(0)
	}
}

func leerImagenes(r io.Reader, n int) [][]float32 {
	x, raw := make([][]float32, n), make([]byte, IN)
	for i := range x {
		leer(r, raw)
		x[i] = make([]float32, IN)
		for j, b := range raw {
			x[i][j] = float32(b)/127.5 - 1
		}
	}
	return x
}

func main() {
	host := "127.0.0.1"
	if len(os.Args) > 1 {
		host = os.Args[1]
	}
	if len(os.Args) > 2 {
		hilos, _ = strconv.Atoi(os.Args[2])
	}
	conn, err := net.Dial("tcp", host+":6000")
	if err != nil {
		fmt.Println("No se pudo conectar:", err)
		os.Exit(1)
	}
	r, w := bufio.NewReaderSize(conn, 1<<16), bufio.NewWriterSize(conn, 1<<16)
	binary.Write(w, binary.BigEndian, uint16(2))
	w.WriteString("go")
	binary.Write(w, binary.BigEndian, int32(hilos))
	w.Flush()
	fmt.Printf("Nodo Go conectado a %s con %d goroutines\n", host, hilos)
	var pesos []float32
	for {
		var tipo byte
		var n int32
		leer(r, &tipo)
		leer(r, &n)
		switch tipo {
		case 'W':
			pesos = make([]float32, n)
			leer(r, pesos)
			continue
		case 'I':
			probs := adelante(pesos, leerImagenes(r, int(n))).probs
			binary.Write(w, binary.BigEndian, int32(len(probs[0])))
			for _, p := range probs {
				binary.Write(w, binary.BigEndian, p)
			}
		case 'T':
			etiquetas := make([]byte, n)
			leer(r, etiquetas)
			x, y := leerImagenes(r, int(n)), make([]int, n)
			for i, e := range etiquetas {
				y[i] = int(e)
			}
			g := make([]float32, len(pesos))
			perdida, aciertos := entrenarLote(pesos, x, y, g)
			binary.Write(w, binary.BigEndian, g)
			binary.Write(w, binary.BigEndian, perdida)
			binary.Write(w, binary.BigEndian, aciertos)
		}
		w.Flush()
		fmt.Printf("%c: %d imágenes\n", tipo, n)
	}
}
