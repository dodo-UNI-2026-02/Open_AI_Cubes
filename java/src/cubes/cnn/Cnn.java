package cubes.cnn;

import static cubes.cnn.Params.*;

/**
 * Red completa: Conv(16) → Pool → Conv(32) → Pool → Dense(128) → Dense(K) → Softmax.
 *
 * Cada capa se ejecuta como un conjunto de micro chunks repartidos entre los hilos
 * del ChunkExecutor. Las capas van en orden (forRange espera a todos los chunks
 * antes de seguir), porque cada capa necesita la salida completa de la anterior.
 */
public final class Cnn {

    /** Resultado de procesar un lote: suma de pérdidas y cantidad de aciertos. */
    public static final class Stats {
        public double lossSum;
        public int correct;
        public int count;

        public void add(Stats o) {
            lossSum += o.lossSum;
            correct += o.correct;
            count += o.count;
        }
    }

    private final ChunkExecutor ex;

    public Cnn(ChunkExecutor ex) {
        this.ex = ex;
    }

    /** Convierte una imagen cruda (planos R, G, B de 1024 bytes) a floats en [-1, 1]. */
    public static float[] normalize(byte[] raw, int off) {
        float[] x = new float[IN_SIZE];
        for (int i = 0; i < IN_SIZE; i++) {
            x[i] = (raw[off + i] & 0xFF) / 127.5f - 1f;
        }
        return x;
    }

    /** Activaciones intermedias de un forward, necesarias para el backward. */
    private static final class Acts {
        float[][] x, a1, p1, a2, p2, h, probs;
        int[][] i1, i2;
    }

    private Acts forward(final float[] p, final int classes, final float[][] x) {
        final int n = x.length;
        final Acts s = new Acts();
        s.x = x;
        s.a1 = new float[n][C1 * H1 * W1S];
        s.p1 = new float[n][C1 * P1H * P1W];
        s.i1 = new int[n][C1 * P1H * P1W];
        s.a2 = new float[n][C2 * H2 * W2S];
        s.p2 = new float[n][FLAT];
        s.i2 = new int[n][FLAT];
        s.h = new float[n][FC1];
        final float[][] logits = new float[n][classes];

        ex.forRange(C1, (a, b) -> ConvMicroChunk.forward(x, IN_C, IN_H, IN_W, p, O_W1, O_B1, s.a1, a, b));
        ex.forRange(C1, (a, b) -> PoolMicroChunk.forward(s.a1, H1, W1S, s.p1, s.i1, a, b));
        ex.forRange(C2, (a, b) -> ConvMicroChunk.forward(s.p1, C1, H2, W2S, p, O_W2, O_B2, s.a2, a, b));
        ex.forRange(C2, (a, b) -> PoolMicroChunk.forward(s.a2, H2, W2S, s.p2, s.i2, a, b));
        ex.forRange(FC1, (a, b) -> MlpMicroChunk.forward(s.p2, FLAT, p, O_W3, O_B3, s.h, true, a, b));
        final int oB4 = O_W4 + FC1 * classes;
        ex.forRange(classes, (a, b) -> MlpMicroChunk.forward(s.h, FC1, p, O_W4, oB4, logits, false, a, b));

        s.probs = new float[n][];
        for (int i = 0; i < n; i++) {
            s.probs[i] = softmax(logits[i]);
        }
        return s;
    }

    private static float[] softmax(float[] z) {
        float max = z[0];
        for (float v : z) {
            max = Math.max(max, v);
        }
        double sum = 0;
        float[] out = new float[z.length];
        for (int i = 0; i < z.length; i++) {
            out[i] = (float) Math.exp(z[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < z.length; i++) {
            out[i] = (float) (out[i] / sum);
        }
        return out;
    }

    public static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Solo inferencia: devuelve las probabilidades por clase de cada imagen. */
    public float[][] predict(Params params, float[][] x) {
        return forward(params.data, params.classes, x).probs;
    }

    /**
     * Forward + backward sobre un lote. Los gradientes (SUMA sobre las imágenes,
     * no promedio) se acumulan en grad, que debe tener el tamaño de params.data.
     */
    public Stats forwardBackward(Params params, final float[][] x, int[] labels, final float[] grad) {
        final float[] p = params.data;
        final int classes = params.classes;
        final int n = x.length;
        final Acts s = forward(p, classes, x);

        Stats st = new Stats();
        st.count = n;
        final float[][] dL = new float[n][classes];
        for (int i = 0; i < n; i++) {
            float[] pr = s.probs[i];
            st.lossSum += -Math.log(Math.max(pr[labels[i]], 1e-12f));
            if (argmax(pr) == labels[i]) {
                st.correct++;
            }
            for (int k = 0; k < classes; k++) {
                dL[i][k] = pr[k] - (k == labels[i] ? 1f : 0f);
            }
        }

        final int oB4 = O_W4 + FC1 * classes;
        // MLP de salida y capa oculta
        ex.forRange(classes, (a, b) -> MlpMicroChunk.backwardWeights(s.h, FC1, dL, grad, O_W4, oB4, a, b));
        final float[][] dH = new float[n][FC1];
        ex.forRange(FC1, (a, b) -> MlpMicroChunk.backwardInput(dL, classes, p, O_W4, FC1, dH, s.h, a, b));
        ex.forRange(FC1, (a, b) -> MlpMicroChunk.backwardWeights(s.p2, FLAT, dH, grad, O_W3, O_B3, a, b));
        final float[][] dP2 = new float[n][FLAT];
        ex.forRange(FLAT, (a, b) -> MlpMicroChunk.backwardInput(dH, FC1, p, O_W3, FLAT, dP2, null, a, b));
        // Bloque convolucional 2
        final float[][] dZ2 = new float[n][C2 * H2 * W2S];
        ex.forRange(C2, (a, b) -> PoolMicroChunk.backward(dP2, s.i2, s.a2, H2, W2S, dZ2, a, b));
        ex.forRange(C2, (a, b) -> ConvMicroChunk.backwardWeights(s.p1, C1, H2, W2S, dZ2, grad, O_W2, O_B2, a, b));
        final float[][] dP1 = new float[n][C1 * P1H * P1W];
        ex.forRange(C1, (a, b) -> ConvMicroChunk.backwardInput(dZ2, C2, p, O_W2, C1, H2, W2S, dP1, a, b));
        // Bloque convolucional 1 (no hace falta el gradiente de la imagen de entrada)
        final float[][] dZ1 = new float[n][C1 * H1 * W1S];
        ex.forRange(C1, (a, b) -> PoolMicroChunk.backward(dP1, s.i1, s.a1, H1, W1S, dZ1, a, b));
        ex.forRange(C1, (a, b) -> ConvMicroChunk.backwardWeights(x, IN_C, IN_H, IN_W, dZ1, grad, O_W1, O_B1, a, b));
        return st;
    }
}
