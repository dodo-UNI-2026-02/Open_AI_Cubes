package cubes.cnn;

/**
 * Micro chunk de convolución 3x3, stride 1, padding 1, seguida de ReLU.
 *
 * Cada método trabaja sobre un rango de filtros (o de canales de entrada) para
 * todas las imágenes del lote. Rangos distintos escriben en zonas distintas de
 * memoria, por eso varios hilos pueden ejecutar chunks a la vez sin bloqueos.
 *
 * Disposición de tensores por imagen: [canal][fila][columna] en un float[] plano.
 */
public final class ConvMicroChunk {

    private ConvMicroChunk() {
    }

    /** Forward + ReLU para los filtros [f0, f1). out[n] tiene outC * h * w valores. */
    public static void forward(float[][] in, int inC, int h, int w, float[] p, int oW, int oB,
                               float[][] out, int f0, int f1) {
        int hw = h * w;
        for (int n = 0; n < in.length; n++) {
            float[] src = in[n];
            float[] dst = out[n];
            for (int f = f0; f < f1; f++) {
                int ob = f * hw;
                float bias = p[oB + f];
                for (int i = 0; i < hw; i++) {
                    dst[ob + i] = bias;
                }
                for (int c = 0; c < inC; c++) {
                    int ib = c * hw;
                    for (int ky = 0; ky < 3; ky++) {
                        int dy = ky - 1;
                        int y0 = Math.max(0, -dy), y1 = Math.min(h, h - dy);
                        for (int kx = 0; kx < 3; kx++) {
                            int dx = kx - 1;
                            int x0 = Math.max(0, -dx), x1 = Math.min(w, w - dx);
                            float wv = p[oW + ((f * inC + c) * 3 + ky) * 3 + kx];
                            for (int y = y0; y < y1; y++) {
                                int orow = ob + y * w;
                                int irow = ib + (y + dy) * w + dx;
                                for (int x = x0; x < x1; x++) {
                                    dst[orow + x] += wv * src[irow + x];
                                }
                            }
                        }
                    }
                }
                for (int i = 0; i < hw; i++) {
                    if (dst[ob + i] < 0f) {
                        dst[ob + i] = 0f;
                    }
                }
            }
        }
    }

    /**
     * Gradiente de pesos y sesgos para los filtros [f0, f1).
     * dZ es el gradiente respecto a la salida antes de la ReLU. Se ACUMULA en g.
     */
    public static void backwardWeights(float[][] in, int inC, int h, int w, float[][] dZ,
                                       float[] g, int oW, int oB, int f0, int f1) {
        int hw = h * w;
        for (int f = f0; f < f1; f++) {
            int ob = f * hw;
            for (int n = 0; n < in.length; n++) {
                float[] src = in[n];
                float[] dz = dZ[n];
                float sb = 0f;
                for (int i = 0; i < hw; i++) {
                    sb += dz[ob + i];
                }
                g[oB + f] += sb;
                for (int c = 0; c < inC; c++) {
                    int ib = c * hw;
                    for (int ky = 0; ky < 3; ky++) {
                        int dy = ky - 1;
                        int y0 = Math.max(0, -dy), y1 = Math.min(h, h - dy);
                        for (int kx = 0; kx < 3; kx++) {
                            int dx = kx - 1;
                            int x0 = Math.max(0, -dx), x1 = Math.min(w, w - dx);
                            float s = 0f;
                            for (int y = y0; y < y1; y++) {
                                int orow = ob + y * w;
                                int irow = ib + (y + dy) * w + dx;
                                for (int x = x0; x < x1; x++) {
                                    s += dz[orow + x] * src[irow + x];
                                }
                            }
                            g[oW + ((f * inC + c) * 3 + ky) * 3 + kx] += s;
                        }
                    }
                }
            }
        }
    }

    /** Gradiente respecto a la entrada para los canales de entrada [c0, c1). Sobrescribe dIn. */
    public static void backwardInput(float[][] dZ, int outC, float[] p, int oW, int inC, int h, int w,
                                     float[][] dIn, int c0, int c1) {
        int hw = h * w;
        for (int n = 0; n < dZ.length; n++) {
            float[] dz = dZ[n];
            float[] di = dIn[n];
            for (int c = c0; c < c1; c++) {
                int ib = c * hw;
                for (int i = 0; i < hw; i++) {
                    di[ib + i] = 0f;
                }
                for (int f = 0; f < outC; f++) {
                    int ob = f * hw;
                    for (int ky = 0; ky < 3; ky++) {
                        int dy = ky - 1;
                        int y0 = Math.max(0, -dy), y1 = Math.min(h, h - dy);
                        for (int kx = 0; kx < 3; kx++) {
                            int dx = kx - 1;
                            int x0 = Math.max(0, -dx), x1 = Math.min(w, w - dx);
                            float wv = p[oW + ((f * inC + c) * 3 + ky) * 3 + kx];
                            if (wv == 0f) {
                                continue;
                            }
                            for (int y = y0; y < y1; y++) {
                                int orow = ob + y * w;
                                int irow = ib + (y + dy) * w + dx;
                                for (int x = x0; x < x1; x++) {
                                    di[irow + x] += wv * dz[orow + x];
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
