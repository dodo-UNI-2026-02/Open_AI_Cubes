package main

// Micro chunks de la CNN: convolución, MLP (capa densa) y max pooling.
// Son traducciones directas de ConvMicroChunk, MlpMicroChunk y PoolMicroChunk de Java.
//
// Cada función trabaja sobre un rango [desde, hasta) de filtros, neuronas o canales,
// para todas las imágenes del lote. Rangos distintos escriben en zonas distintas de
// memoria, por eso varias goroutines pueden ejecutar chunks a la vez sin bloqueos.
//
// Disposición de tensores por imagen: [canal][fila][columna] en un []float32 plano.
//
// Nota de rendimiento (específica de Go): en los bucles internos se toman sub-slices
// del mismo largo (d := dst[a:b]; s := src[c:c+len(d)]) para que el compilador elimine
// la comprobación de límites en cada acceso. El orden de las operaciones es el mismo
// que en Java.

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// ===========================================================================
// ConvMicroChunk: convolución 3x3, stride 1, padding 1, seguida de ReLU
// ===========================================================================

// convForward: forward + ReLU para los filtros [f0, f1). out[n] tiene outC*h*w valores.
func convForward(in [][]float32, inC, h, w int, p []float32, oW, oB int,
	out [][]float32, f0, f1 int) {
	hw := h * w
	for n := range in {
		src := in[n][:inC*hw]
		dst := out[n]
		for f := f0; f < f1; f++ {
			o := dst[f*hw : (f+1)*hw]
			bias := p[oB+f]
			for i := range o {
				o[i] = bias
			}
			for c := 0; c < inC; c++ {
				ib := c * hw
				for ky := 0; ky < 3; ky++ {
					dy := ky - 1
					y0, y1 := maxInt(0, -dy), minInt(h, h-dy)
					for kx := 0; kx < 3; kx++ {
						dx := kx - 1
						x0, x1 := maxInt(0, -dx), minInt(w, w-dx)
						wv := p[oW+((f*inC+c)*3+ky)*3+kx]
						for y := y0; y < y1; y++ {
							orow := y * w
							irow := ib + (y+dy)*w + dx
							d := o[orow+x0 : orow+x1]
							s := src[irow+x0 : irow+x0+len(d)]
							for x := range d {
								d[x] += wv * s[x]
							}
						}
					}
				}
			}
			for i := range o {
				if o[i] < 0 {
					o[i] = 0
				}
			}
		}
	}
}

// convBackwardWeights: gradiente de pesos y sesgos para los filtros [f0, f1).
// dZ es el gradiente respecto a la salida antes de la ReLU. Se ACUMULA en g.
func convBackwardWeights(in [][]float32, inC, h, w int, dZ [][]float32,
	g []float32, oW, oB int, f0, f1 int) {
	hw := h * w
	for f := f0; f < f1; f++ {
		ob := f * hw
		for n := range in {
			src := in[n][:inC*hw]
			dz := dZ[n][ob : ob+hw]
			var sb float32
			for _, v := range dz {
				sb += v
			}
			g[oB+f] += sb
			for c := 0; c < inC; c++ {
				ib := c * hw
				for ky := 0; ky < 3; ky++ {
					dy := ky - 1
					y0, y1 := maxInt(0, -dy), minInt(h, h-dy)
					for kx := 0; kx < 3; kx++ {
						dx := kx - 1
						x0, x1 := maxInt(0, -dx), minInt(w, w-dx)
						var s float32
						for y := y0; y < y1; y++ {
							orow := y * w
							irow := ib + (y+dy)*w + dx
							d := dz[orow+x0 : orow+x1]
							a := src[irow+x0 : irow+x0+len(d)]
							for x := range d {
								s += d[x] * a[x]
							}
						}
						g[oW+((f*inC+c)*3+ky)*3+kx] += s
					}
				}
			}
		}
	}
}

// convBackwardInput: gradiente respecto a la entrada para los canales de entrada
// [c0, c1). Sobrescribe dIn.
func convBackwardInput(dZ [][]float32, outC int, p []float32, oW, inC, h, w int,
	dIn [][]float32, c0, c1 int) {
	hw := h * w
	for n := range dZ {
		dz := dZ[n][:outC*hw]
		di := dIn[n]
		for c := c0; c < c1; c++ {
			dc := di[c*hw : (c+1)*hw]
			for i := range dc {
				dc[i] = 0
			}
			for f := 0; f < outC; f++ {
				ob := f * hw
				for ky := 0; ky < 3; ky++ {
					dy := ky - 1
					y0, y1 := maxInt(0, -dy), minInt(h, h-dy)
					for kx := 0; kx < 3; kx++ {
						dx := kx - 1
						x0, x1 := maxInt(0, -dx), minInt(w, w-dx)
						wv := p[oW+((f*inC+c)*3+ky)*3+kx]
						if wv == 0 {
							continue
						}
						for y := y0; y < y1; y++ {
							orow := ob + y*w
							irow := (y+dy)*w + dx
							d := dc[irow+x0 : irow+x1]
							s := dz[orow+x0 : orow+x0+len(d)]
							for x := range d {
								d[x] += wv * s[x]
							}
						}
					}
				}
			}
		}
	}
}

// ===========================================================================
// MlpMicroChunk: perceptrón multicapa (capa totalmente conectada)
// W tiene forma [out][in] guardada fila por fila desde el desplazamiento oW.
// ===========================================================================

// mlpForward: out[n][j] = b[j] + W[j] · in[n], con ReLU opcional, para j en [j0, j1).
func mlpForward(in [][]float32, inSize int, p []float32, oW, oB int, out [][]float32,
	relu bool, j0, j1 int) {
	for n := range in {
		x := in[n][:inSize]
		y := out[n]
		for j := j0; j < j1; j++ {
			row := p[oW+j*inSize : oW+(j+1)*inSize]
			s := p[oB+j]
			for i, wv := range row {
				s += wv * x[i]
			}
			if relu && s < 0 {
				s = 0
			}
			y[j] = s
		}
	}
}

// mlpBackwardWeights acumula en g el gradiente de W y b para las neuronas [j0, j1).
// dZ: gradiente antes de la activación.
func mlpBackwardWeights(in [][]float32, inSize int, dZ [][]float32, g []float32, oW, oB int,
	j0, j1 int) {
	for j := j0; j < j1; j++ {
		row := g[oW+j*inSize : oW+(j+1)*inSize]
		for n := range in {
			d := dZ[n][j]
			if d == 0 {
				continue
			}
			g[oB+j] += d
			x := in[n][:len(row)]
			for i := range row {
				row[i] += d * x[i]
			}
		}
	}
}

// mlpBackwardInput: dIn[n][i] = sum_j W[j][i] * dZ[n][j] para i en [i0, i1).
// Si reluAct no es nil, multiplica por la derivada de la ReLU de la capa anterior (act > 0).
func mlpBackwardInput(dZ [][]float32, outSize int, p []float32, oW, inSize int,
	dIn [][]float32, reluAct [][]float32, i0, i1 int) {
	for n := range dZ {
		di := dIn[n][i0:i1]
		dz := dZ[n][:outSize]
		for i := range di {
			di[i] = 0
		}
		for j, d := range dz {
			if d == 0 {
				continue
			}
			row := oW + j*inSize
			wr := p[row+i0 : row+i0+len(di)]
			for i := range di {
				di[i] += d * wr[i]
			}
		}
		if reluAct != nil {
			a := reluAct[n][i0 : i0+len(di)]
			for i := range di {
				if a[i] <= 0 {
					di[i] = 0
				}
			}
		}
	}
}

// ===========================================================================
// PoolMicroChunk: max pooling 2x2 con stride 2, por rango de canales [c0, c1).
// Guarda la posición del máximo para enrutar el gradiente en el backward.
// Empates: gana la primera posición en el orden (0,0), (0,1), (1,0), (1,1).
// ===========================================================================

func poolForward(in [][]float32, h, w int, out [][]float32, argmax [][]int32, c0, c1 int) {
	ph, pw := h/2, w/2
	for n := range in {
		src := in[n]
		dst := out[n]
		am := argmax[n]
		for c := c0; c < c1; c++ {
			ib := c * h * w
			ob := c * ph * pw
			for py := 0; py < ph; py++ {
				for px := 0; px < pw; px++ {
					base := ib + (2*py)*w + 2*px
					best := base
					bv := src[base]
					i1, i2, i3 := base+1, base+w, base+w+1
					if src[i1] > bv {
						bv, best = src[i1], i1
					}
					if src[i2] > bv {
						bv, best = src[i2], i2
					}
					if src[i3] > bv {
						bv, best = src[i3], i3
					}
					dst[ob+py*pw+px] = bv
					am[ob+py*pw+px] = int32(best)
				}
			}
		}
	}
}

// poolBackward enruta dOut a la posición del máximo y aplica la derivada de la ReLU
// previa (act > 0). dZ debe venir en cero; solo se escriben los canales [c0, c1).
func poolBackward(dOut [][]float32, argmax [][]int32, act [][]float32, h, w int,
	dZ [][]float32, c0, c1 int) {
	pp := (h / 2) * (w / 2)
	for n := range dOut {
		d := dOut[n]
		am := argmax[n]
		a := act[n]
		dz := dZ[n]
		for k := c0 * pp; k < c1*pp; k++ {
			idx := am[k]
			if a[idx] > 0 {
				dz[idx] = d[k]
			}
		}
	}
}
