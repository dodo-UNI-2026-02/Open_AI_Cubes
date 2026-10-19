package cubes.cnn;

/**
 * Micro chunk de max pooling 2x2 con stride 2, por rango de canales [c0, c1).
 * Guarda la posición del máximo para enrutar el gradiente en el backward.
 * Empates: gana la primera posición en el orden (0,0), (0,1), (1,0), (1,1).
 */
public final class PoolMicroChunk {

    private PoolMicroChunk() {
    }

    public static void forward(float[][] in, int h, int w, float[][] out, int[][] argmax, int c0, int c1) {
        int ph = h / 2, pw = w / 2;
        for (int n = 0; n < in.length; n++) {
            float[] src = in[n];
            float[] dst = out[n];
            int[] am = argmax[n];
            for (int c = c0; c < c1; c++) {
                int ib = c * h * w;
                int ob = c * ph * pw;
                for (int py = 0; py < ph; py++) {
                    for (int px = 0; px < pw; px++) {
                        int base = ib + (2 * py) * w + 2 * px;
                        int best = base;
                        float bv = src[base];
                        int i1 = base + 1, i2 = base + w, i3 = base + w + 1;
                        if (src[i1] > bv) { bv = src[i1]; best = i1; }
                        if (src[i2] > bv) { bv = src[i2]; best = i2; }
                        if (src[i3] > bv) { bv = src[i3]; best = i3; }
                        dst[ob + py * pw + px] = bv;
                        am[ob + py * pw + px] = best;
                    }
                }
            }
        }
    }

    /**
     * Enruta dOut a la posición del máximo y aplica la derivada de la ReLU previa
     * (act > 0). dZ debe venir en cero; solo se escriben los canales [c0, c1).
     */
    public static void backward(float[][] dOut, int[][] argmax, float[][] act, int h, int w,
                                float[][] dZ, int c0, int c1) {
        int pp = (h / 2) * (w / 2);
        for (int n = 0; n < dOut.length; n++) {
            float[] d = dOut[n];
            int[] am = argmax[n];
            float[] a = act[n];
            float[] dz = dZ[n];
            for (int k = c0 * pp; k < c1 * pp; k++) {
                int idx = am[k];
                if (a[idx] > 0f) {
                    dz[idx] = d[k];
                }
            }
        }
    }
}
