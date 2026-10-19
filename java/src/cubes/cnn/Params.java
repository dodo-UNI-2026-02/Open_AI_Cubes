package cubes.cnn;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Pesos de la CNN guardados en un solo arreglo float[] con desplazamientos fijos.
 * El mismo orden se usa en los archivos de pesos, en el protocolo y en los nodos
 * Python y Go (ver docs/CNN_SPEC.md):
 *
 *   W1[16][3][3][3]  b1[16]       conv1
 *   W2[32][16][3][3] b2[32]       conv2
 *   W3[128][2048]    b3[128]      fc1 (MLP oculta)
 *   W4[K][128]       b4[K]        fc2 (salida, K clases)
 */
public final class Params {

    public static final int IN_C = 3, IN_H = 32, IN_W = 32, IN_SIZE = IN_C * IN_H * IN_W;
    public static final int KS = 3;
    public static final int C1 = 16, H1 = 32, W1S = 32;        // salida conv1 (padding 1)
    public static final int P1H = 16, P1W = 16;                // salida pool1
    public static final int C2 = 32, H2 = 16, W2S = 16;        // salida conv2
    public static final int P2H = 8, P2W = 8;                  // salida pool2
    public static final int FLAT = C2 * P2H * P2W;             // 2048
    public static final int FC1 = 128;

    public static final int N_W1 = C1 * IN_C * KS * KS;
    public static final int N_W2 = C2 * C1 * KS * KS;
    public static final int N_W3 = FC1 * FLAT;
    /** Cantidad de parámetros que no dependen del número de clases. */
    public static final int FIXED = N_W1 + C1 + N_W2 + C2 + N_W3 + FC1;

    public static final int O_W1 = 0;
    public static final int O_B1 = O_W1 + N_W1;
    public static final int O_W2 = O_B1 + C1;
    public static final int O_B2 = O_W2 + N_W2;
    public static final int O_W3 = O_B2 + C2;
    public static final int O_B3 = O_W3 + N_W3;
    public static final int O_W4 = O_B3 + FC1;

    private static final int FILE_MAGIC = 0x4F435731; // "OCW1"

    public final int classes;
    public final int oB4;
    public final float[] data;

    public Params(int classes) {
        this.classes = classes;
        this.oB4 = O_W4 + FC1 * classes;
        this.data = new float[size(classes)];
    }

    public static int size(int classes) {
        return FIXED + FC1 * classes + classes;
    }

    /** Deduce el número de clases a partir del tamaño del vector de pesos. */
    public static int classesFromSize(int count) {
        int k = (count - FIXED) / (FC1 + 1);
        if (k <= 0 || size(k) != count) {
            throw new IllegalArgumentException("Tamaño de pesos inválido: " + count);
        }
        return k;
    }

    public static Params fromArray(float[] values) {
        Params p = new Params(classesFromSize(values.length));
        System.arraycopy(values, 0, p.data, 0, values.length);
        return p;
    }

    public Params copy() {
        Params p = new Params(classes);
        System.arraycopy(data, 0, p.data, 0, data.length);
        return p;
    }

    /** Inicialización He (normal con desviación sqrt(2 / fan_in)), sesgos en cero. */
    public static Params randomInit(int classes, long seed) {
        Params p = new Params(classes);
        Random r = new Random(seed);
        fill(p.data, O_W1, N_W1, Math.sqrt(2.0 / (IN_C * KS * KS)), r);
        fill(p.data, O_W2, N_W2, Math.sqrt(2.0 / (C1 * KS * KS)), r);
        fill(p.data, O_W3, N_W3, Math.sqrt(2.0 / FLAT), r);
        fill(p.data, O_W4, FC1 * classes, Math.sqrt(1.0 / FC1), r);
        return p;
    }

    private static void fill(float[] a, int off, int n, double std, Random r) {
        for (int i = 0; i < n; i++) {
            a[off + i] = (float) (r.nextGaussian() * std);
        }
    }

    public void save(File f) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16));
        try {
            out.writeInt(FILE_MAGIC);
            out.writeInt(data.length);
            for (float v : data) {
                out.writeFloat(v);
            }
        } finally {
            out.close();
        }
    }

    public static Params load(File f) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
        try {
            if (in.readInt() != FILE_MAGIC) {
                throw new IOException("No es un archivo de pesos OCW1: " + f);
            }
            int n = in.readInt();
            float[] v = new float[n];
            for (int i = 0; i < n; i++) {
                v[i] = in.readFloat();
            }
            return fromArray(v);
        } finally {
            in.close();
        }
    }
}
