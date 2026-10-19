package cubes.cnn;

/**
 * Micro chunk de perceptrón multicapa (capa totalmente conectada).
 *
 * forward y backwardWeights trabajan sobre un rango de neuronas [j0, j1);
 * backwardInput trabaja sobre un rango de entradas [i0, i1). Cada hilo escribe
 * solo en su rango, así que no hay condiciones de carrera.
 *
 * W tiene forma [out][in] guardada fila por fila desde el desplazamiento oW.
 */
public final class MlpMicroChunk {

    private MlpMicroChunk() {
    }

    /** out[n][j] = b[j] + W[j] · in[n], con ReLU opcional, para j en [j0, j1). */
    public static void forward(float[][] in, int inSize, float[] p, int oW, int oB, float[][] out,
                               boolean relu, int j0, int j1) {
        for (int n = 0; n < in.length; n++) {
            float[] x = in[n];
            float[] y = out[n];
            for (int j = j0; j < j1; j++) {
                int row = oW + j * inSize;
                float s = p[oB + j];
                for (int i = 0; i < inSize; i++) {
                    s += p[row + i] * x[i];
                }
                y[j] = (relu && s < 0f) ? 0f : s;
            }
        }
    }

    /** Acumula en g el gradiente de W y b para las neuronas [j0, j1). dZ: gradiente antes de la activación. */
    public static void backwardWeights(float[][] in, int inSize, float[][] dZ, float[] g, int oW, int oB,
                                       int j0, int j1) {
        for (int j = j0; j < j1; j++) {
            int row = oW + j * inSize;
            for (int n = 0; n < in.length; n++) {
                float d = dZ[n][j];
                if (d == 0f) {
                    continue;
                }
                g[oB + j] += d;
                float[] x = in[n];
                for (int i = 0; i < inSize; i++) {
                    g[row + i] += d * x[i];
                }
            }
        }
    }

    /**
     * dIn[n][i] = sum_j W[j][i] * dZ[n][j] para i en [i0, i1). Si reluAct no es null,
     * multiplica por la derivada de la ReLU de la capa anterior (act > 0).
     */
    public static void backwardInput(float[][] dZ, int outSize, float[] p, int oW, int inSize,
                                     float[][] dIn, float[][] reluAct, int i0, int i1) {
        for (int n = 0; n < dZ.length; n++) {
            float[] di = dIn[n];
            float[] dz = dZ[n];
            for (int i = i0; i < i1; i++) {
                di[i] = 0f;
            }
            for (int j = 0; j < outSize; j++) {
                float d = dz[j];
                if (d == 0f) {
                    continue;
                }
                int row = oW + j * inSize;
                for (int i = i0; i < i1; i++) {
                    di[i] += d * p[row + i];
                }
            }
            if (reluAct != null) {
                float[] a = reluAct[n];
                for (int i = i0; i < i1; i++) {
                    if (a[i] <= 0f) {
                        di[i] = 0f;
                    }
                }
            }
        }
    }
}
