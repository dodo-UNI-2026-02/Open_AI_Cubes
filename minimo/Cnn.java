import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Red convolucional de Open AI Cubes: Conv(16) -> Pool -> Conv(32) -> Pool -> Dense(128) -> Dense(K) -> Softmax.
 * Misma arquitectura y mismo formato de pesos que la v1.0, así que usa data/model.bin tal cual.
 *
 * Micro chunks: cada capa se parte en rangos de filtros o de neuronas y cada hilo calcula uno.
 * invokeAll espera a todos los rangos antes de seguir (barrera por capa), porque cada capa
 * necesita la salida completa de la anterior.
 *
 * Uso:  java Cnn prueba [hilos]                            acierto de data/model.bin en data/test.bin
 *       java Cnn entrenar <hilos> <imagenes> [epocas]       entrenamiento local (1 hilo = secuencial)
 */
public class Cnn {
    // Tamaños de la red y posición de cada bloque dentro del arreglo de pesos.
    static final int C1 = 16, C2 = 32, FC1 = 128, FLAT = C2 * 8 * 8, IN = 3 * 32 * 32;
    static final int W1 = 0, B1 = W1 + C1 * 3 * 9, W2 = B1 + C1, B2 = W2 + C2 * C1 * 9;
    static final int W3 = B2 + C2, B3 = W3 + FC1 * FLAT, W4 = B3 + FC1;
    static final String DATA = System.getProperty("data", "../data/");

    final int hilos;
    private final ExecutorService pool;

    Cnn(int hilos) {
        this.hilos = Math.max(1, hilos);
        this.pool = this.hilos > 1 ? Executors.newFixedThreadPool(this.hilos, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);                      // no impide que el programa termine
            return t;
        }) : null;
    }

    static int clases(float[] p) {
        return (p.length - W4) / (FC1 + 1);
    }

    // ---------- Reparto en micro chunks ----------

    interface Rango {
        void run(int a, int b);
    }

    /** Divide [0, n) en un rango por hilo y espera a que todos terminen. */
    void paralelo(int n, Rango r) {
        if (pool == null) {
            r.run(0, n);
            return;
        }
        List<Callable<Object>> tareas = new ArrayList<Callable<Object>>();
        for (int t = 0; t < hilos; t++) {
            final int a = n * t / hilos, b = n * (t + 1) / hilos;
            if (a < b) tareas.add(Executors.callable(() -> r.run(a, b)));
        }
        try {
            for (Future<Object> f : pool.invokeAll(tareas)) f.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- Micro chunks: convolución, pooling y MLP ----------

    /** Convolución 3x3 con padding 1 y ReLU, para los filtros f0..f1. */
    static void conv(float[][] in, int inC, int s, float[] p, int ow, int ob, float[][] out, int f0, int f1) {
        int hw = s * s;
        for (int n = 0; n < in.length; n++)
            for (int f = f0; f < f1; f++)
                for (int y = 0; y < s; y++)
                    for (int x = 0; x < s; x++) {
                        float sum = p[ob + f];
                        for (int c = 0; c < inC; c++)
                            for (int ky = 0; ky < 3; ky++) {
                                int yy = y + ky - 1;
                                if (yy < 0 || yy >= s) continue;
                                for (int kx = 0; kx < 3; kx++) {
                                    int xx = x + kx - 1;
                                    if (xx >= 0 && xx < s) sum += p[ow + ((f * inC + c) * 3 + ky) * 3 + kx] * in[n][c * hw + yy * s + xx];
                                }
                            }
                        out[n][f * hw + y * s + x] = Math.max(0f, sum);
                    }
    }

    /** Gradiente de los pesos de los filtros f0..f1 (dz ya incluye la derivada de ReLU). */
    static void convPesos(float[][] in, int inC, int s, float[][] dz, float[] g, int ow, int ob, int f0, int f1) {
        int hw = s * s;
        for (int n = 0; n < in.length; n++)
            for (int f = f0; f < f1; f++)
                for (int y = 0; y < s; y++)
                    for (int x = 0; x < s; x++) {
                        float d = dz[n][f * hw + y * s + x];
                        if (d == 0f) continue;
                        g[ob + f] += d;
                        for (int c = 0; c < inC; c++)
                            for (int ky = 0; ky < 3; ky++) {
                                int yy = y + ky - 1;
                                if (yy < 0 || yy >= s) continue;
                                for (int kx = 0; kx < 3; kx++) {
                                    int xx = x + kx - 1;
                                    if (xx >= 0 && xx < s) g[ow + ((f * inC + c) * 3 + ky) * 3 + kx] += d * in[n][c * hw + yy * s + xx];
                                }
                            }
                    }
    }

    /** Gradiente de la entrada de una convolución, para los canales c0..c1. */
    static void convEntrada(float[][] dz, int outC, int inC, int s, float[] p, int ow, float[][] din, int c0, int c1) {
        int hw = s * s;
        for (int n = 0; n < dz.length; n++)
            for (int c = c0; c < c1; c++)
                for (int f = 0; f < outC; f++)
                    for (int y = 0; y < s; y++)
                        for (int x = 0; x < s; x++) {
                            float d = dz[n][f * hw + y * s + x];
                            if (d == 0f) continue;
                            for (int ky = 0; ky < 3; ky++) {
                                int yy = y + ky - 1;
                                if (yy < 0 || yy >= s) continue;
                                for (int kx = 0; kx < 3; kx++) {
                                    int xx = x + kx - 1;
                                    if (xx >= 0 && xx < s) din[n][c * hw + yy * s + xx] += p[ow + ((f * inC + c) * 3 + ky) * 3 + kx] * d;
                                }
                            }
                        }
    }

    /** Max pooling 2x2 de los canales c0..c1; guarda dónde estaba el máximo. */
    static void pool(float[][] in, int s, float[][] out, int[][] idx, int c0, int c1) {
        int h = s / 2;
        for (int n = 0; n < in.length; n++)
            for (int c = c0; c < c1; c++)
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < h; x++) {
                        int base = c * s * s + 2 * y * s + 2 * x, best = base;
                        for (int k : new int[]{base + 1, base + s, base + s + 1}) if (in[n][k] > in[n][best]) best = k;
                        int o = c * h * h + y * h + x;
                        out[n][o] = in[n][best];
                        idx[n][o] = best;
                    }
    }

    /** El gradiente vuelve solo a la posición del máximo (y solo si pasó la ReLU). */
    static void poolAtras(float[][] dout, int[][] idx, float[][] act, float[][] dz, int per, int c0, int c1) {
        for (int n = 0; n < dout.length; n++)
            for (int k = c0 * per; k < c1 * per; k++) {
                int i = idx[n][k];
                if (act[n][i] > 0f) dz[n][i] = dout[n][k];
            }
    }

    /** Capa densa (MLP) para las neuronas j0..j1. */
    static void densa(float[][] in, int inN, float[] p, int ow, int ob, float[][] out, boolean relu, int j0, int j1) {
        for (int n = 0; n < in.length; n++)
            for (int j = j0; j < j1; j++) {
                float s = p[ob + j];
                for (int i = 0; i < inN; i++) s += p[ow + j * inN + i] * in[n][i];
                out[n][j] = relu ? Math.max(0f, s) : s;
            }
    }

    static void densaPesos(float[][] in, int inN, float[][] dz, float[] g, int ow, int ob, int j0, int j1) {
        for (int n = 0; n < in.length; n++)
            for (int j = j0; j < j1; j++) {
                float d = dz[n][j];
                if (d == 0f) continue;
                g[ob + j] += d;
                for (int i = 0; i < inN; i++) g[ow + j * inN + i] += d * in[n][i];
            }
    }

    /** Gradiente de la entrada de una capa densa, para las entradas i0..i1 (act != null aplica ReLU). */
    static void densaEntrada(float[][] dz, int outN, int inN, float[] p, int ow, float[][] din, float[][] act, int i0, int i1) {
        for (int n = 0; n < dz.length; n++)
            for (int i = i0; i < i1; i++) {
                float s = 0f;
                for (int j = 0; j < outN; j++) s += dz[n][j] * p[ow + j * inN + i];
                din[n][i] = (act != null && act[n][i] <= 0f) ? 0f : s;
            }
    }

    // ---------- Red completa ----------

    private static final class Act {
        float[][] x, a1, p1, a2, p2, h, probs;
        int[][] i1, i2;
    }

    private Act adelante(final float[] p, final float[][] x) {
        final int n = x.length, k = clases(p), b4 = W4 + FC1 * k;
        final Act a = new Act();
        a.x = x;
        a.a1 = new float[n][C1 * 1024];
        a.p1 = new float[n][C1 * 256];
        a.i1 = new int[n][C1 * 256];
        a.a2 = new float[n][C2 * 256];
        a.p2 = new float[n][FLAT];
        a.i2 = new int[n][FLAT];
        a.h = new float[n][FC1];
        final float[][] z = new float[n][k];
        paralelo(C1, (f0, f1) -> conv(x, 3, 32, p, W1, B1, a.a1, f0, f1));
        paralelo(C1, (c0, c1) -> pool(a.a1, 32, a.p1, a.i1, c0, c1));
        paralelo(C2, (f0, f1) -> conv(a.p1, C1, 16, p, W2, B2, a.a2, f0, f1));
        paralelo(C2, (c0, c1) -> pool(a.a2, 16, a.p2, a.i2, c0, c1));
        paralelo(FC1, (j0, j1) -> densa(a.p2, FLAT, p, W3, B3, a.h, true, j0, j1));
        paralelo(k, (j0, j1) -> densa(a.h, FC1, p, W4, b4, z, false, j0, j1));
        a.probs = new float[n][];
        for (int i = 0; i < n; i++) a.probs[i] = softmax(z[i]);
        return a;
    }

    float[][] predecir(float[] p, float[][] x) {
        return adelante(p, x).probs;
    }

    /** Suma en g los gradientes del lote y devuelve {pérdida total, aciertos}. */
    double[] entrenarLote(final float[] p, float[][] x, int[] etiquetas, final float[] g) {
        final int n = x.length, k = clases(p), b4 = W4 + FC1 * k;
        final Act a = adelante(p, x);
        double perdida = 0;
        int aciertos = 0;
        final float[][] dz = new float[n][k];
        for (int i = 0; i < n; i++) {
            perdida -= Math.log(Math.max(a.probs[i][etiquetas[i]], 1e-12f));
            if (argmax(a.probs[i]) == etiquetas[i]) aciertos++;
            for (int c = 0; c < k; c++) dz[i][c] = a.probs[i][c] - (c == etiquetas[i] ? 1f : 0f);
        }
        final float[][] dh = new float[n][FC1], dp2 = new float[n][FLAT];
        final float[][] dz2 = new float[n][C2 * 256], dp1 = new float[n][C1 * 256], dz1 = new float[n][C1 * 1024];
        paralelo(k, (j0, j1) -> densaPesos(a.h, FC1, dz, g, W4, b4, j0, j1));
        paralelo(FC1, (i0, i1) -> densaEntrada(dz, k, FC1, p, W4, dh, a.h, i0, i1));
        paralelo(FC1, (j0, j1) -> densaPesos(a.p2, FLAT, dh, g, W3, B3, j0, j1));
        paralelo(FLAT, (i0, i1) -> densaEntrada(dh, FC1, FLAT, p, W3, dp2, null, i0, i1));
        paralelo(C2, (c0, c1) -> poolAtras(dp2, a.i2, a.a2, dz2, 64, c0, c1));
        paralelo(C2, (f0, f1) -> convPesos(a.p1, C1, 16, dz2, g, W2, B2, f0, f1));
        paralelo(C1, (c0, c1) -> convEntrada(dz2, C2, C1, 16, p, W2, dp1, c0, c1));
        paralelo(C1, (c0, c1) -> poolAtras(dp1, a.i1, a.a1, dz1, 256, c0, c1));
        paralelo(C1, (f0, f1) -> convPesos(a.x, 3, 32, dz1, g, W1, B1, f0, f1));
        return new double[]{perdida, aciertos};
    }

    static float[] softmax(float[] z) {
        float max = z[0];
        for (float v : z) max = Math.max(max, v);
        double sum = 0;
        float[] out = new float[z.length];
        for (int i = 0; i < z.length; i++) sum += out[i] = (float) Math.exp(z[i] - max);
        for (int i = 0; i < z.length; i++) out[i] /= sum;
        return out;
    }

    static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    /** SGD con momento: w -= lr * v, con v = mom * v + gradiente promedio. */
    static void sgd(float[] w, float[] vel, float[] g, int n, float lr, float mom) {
        for (int i = 0; i < w.length; i++) {
            vel[i] = mom * vel[i] + g[i] / n;
            w[i] -= lr * vel[i];
        }
    }

    // ---------- Datos: pesos, imágenes y resultados ----------

    static float[] pesosIniciales(int k, long semilla) {
        float[] p = new float[W4 + FC1 * k + k];
        Random r = new Random(semilla);
        int[][] bloques = {{W1, C1 * 27, 27}, {W2, C2 * C1 * 9, C1 * 9}, {W3, FC1 * FLAT, FLAT}, {W4, FC1 * k, FC1 * 2}};
        for (int[] b : bloques)
            for (int i = 0; i < b[1]; i++) p[b[0] + i] = (float) (r.nextGaussian() * Math.sqrt(2.0 / b[2]));
        return p;
    }

    static float[] cargarPesos(String archivo) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(archivo)))) {
            if (in.readInt() != 0x4F435731) throw new IOException("No es un archivo de pesos OCW1");
            float[] p = new float[in.readInt()];
            for (int i = 0; i < p.length; i++) p[i] = in.readFloat();
            return p;
        }
    }

    static void guardarPesos(float[] p, String archivo) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(archivo)))) {
            out.writeInt(0x4F435731);
            out.writeInt(p.length);
            for (float v : p) out.writeFloat(v);
        }
    }

    /** Lee un .bin estilo CIFAR: por imagen, 1 byte de etiqueta y 3072 de píxeles (planos R, G, B). */
    static byte[] cargarDataset(String nombre) throws IOException {
        return Files.readAllBytes(Paths.get(DATA + nombre));
    }

    static int etiqueta(byte[] ds, int i) {
        return ds[i * (IN + 1)] & 0xFF;
    }

    static float[] normalizar(byte[] ds, int off) {
        float[] x = new float[IN];
        for (int i = 0; i < IN; i++) x[i] = (ds[off + i] & 0xFF) / 127.5f - 1f;
        return x;
    }

    static float[] imagenDe(byte[] ds, int i) {
        return normalizar(ds, i * (IN + 1) + 1);
    }

    static String[] clasesNombres() throws IOException {
        return Files.readAllLines(Paths.get(DATA + "classes.txt")).stream().filter(s -> !s.trim().isEmpty()).toArray(String[]::new);
    }

    /** Recorta el cuadrado central de cualquier foto y la reduce a 32x32 en planos R, G, B. */
    static byte[] aCifar(BufferedImage img) {
        int lado = Math.min(img.getWidth(), img.getHeight());
        Image chica = img.getSubimage((img.getWidth() - lado) / 2, (img.getHeight() - lado) / 2, lado, lado)
                .getScaledInstance(32, 32, Image.SCALE_AREA_AVERAGING);
        BufferedImage b = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        b.getGraphics().drawImage(chica, 0, 0, null);
        byte[] out = new byte[IN];
        for (int i = 0; i < 1024; i++) {
            int rgb = b.getRGB(i % 32, i / 32);
            out[i] = (byte) (rgb >> 16);
            out[1024 + i] = (byte) (rgb >> 8);
            out[2048 + i] = (byte) rgb;
        }
        return out;
    }

    /** Acierto sobre las primeras n imágenes de test.bin. */
    double acierto(float[] p, byte[] test, int n) {
        int ok = 0;
        for (int i = 0; i < n; i += 64) {
            int m = Math.min(64, n - i);
            float[][] x = new float[m][];
            for (int j = 0; j < m; j++) x[j] = imagenDe(test, i + j);
            float[][] pr = predecir(p, x);
            for (int j = 0; j < m; j++) if (argmax(pr[j]) == etiqueta(test, i + j)) ok++;
        }
        return (double) ok / n;
    }

    static void csv(String archivo, String cabecera, String fila) throws IOException {
        boolean nuevo = !new File(archivo).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(archivo, true))) {
            if (nuevo) w.println(cabecera);
            w.println(fila);
        }
    }

    static final String CABECERA = "escenario,lenguajes,nodos,hilos,imagenes,epocas,segundos,img_por_s,acierto_test";

    public static void main(String[] args) throws Exception {
        String modo = args.length > 0 ? args[0] : "prueba";
        byte[] test = cargarDataset("test.bin");
        int nTest = test.length / (IN + 1);
        if (modo.equals("prueba")) {
            Cnn cnn = new Cnn(args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors());
            long t0 = System.nanoTime();
            double acc = cnn.acierto(cargarPesos(DATA + "model.bin"), test, nTest);
            System.out.printf("Acierto de model.bin en %d imágenes: %.1f %% (%.1f s, %d hilos)%n", nTest, acc * 100, (System.nanoTime() - t0) / 1e9, cnn.hilos);
        } else {
            // Entrenamiento local: escenario secuencial (1 hilo) o paralelo (varios hilos).
            Cnn cnn = new Cnn(Integer.parseInt(args[1]));
            byte[] train = cargarDataset("train.bin");
            int imagenes = Math.min(Integer.parseInt(args[2]), train.length / (IN + 1));
            int epocas = args.length > 3 ? Integer.parseInt(args[3]) : 1;
            float[] w = pesosIniciales(4, 7), vel = new float[w.length];
            long t0 = System.nanoTime();
            for (int e = 1; e <= epocas; e++) {
                double perdida = 0;
                int aciertos = 0;
                for (int i = 0; i < imagenes; i += 64) {
                    int m = Math.min(64, imagenes - i);
                    float[][] x = new float[m][];
                    int[] y = new int[m];
                    for (int j = 0; j < m; j++) {
                        x[j] = imagenDe(train, i + j);
                        y[j] = etiqueta(train, i + j);
                    }
                    float[] g = new float[w.length];
                    double[] r = cnn.entrenarLote(w, x, y, g);
                    perdida += r[0];
                    aciertos += (int) r[1];
                    sgd(w, vel, g, m, 0.01f, 0.9f);
                }
                System.out.printf("Época %d: pérdida %.4f, acierto en entrenamiento %.1f %%%n", e, perdida / imagenes, 100.0 * aciertos / imagenes);
            }
            double seg = (System.nanoTime() - t0) / 1e9, acc = cnn.acierto(w, test, Math.min(1000, nTest));
            System.out.printf("%d imágenes x %d épocas en %.2f s (%.1f img/s), acierto en prueba %.1f %%%n", imagenes, epocas, seg, imagenes * epocas / seg, acc * 100);
            String escenario = cnn.hilos == 1 ? "secuencial" : "paralelo";
            csv("resultados.csv", CABECERA, String.format(Locale.US, "%s,java,1,%d,%d,%d,%.3f,%.2f,%.4f", escenario, cnn.hilos, imagenes, epocas, seg, imagenes * epocas / seg, acc));
            if (System.getProperty("guardar") != null) guardarPesos(w, System.getProperty("guardar"));
        }
        System.exit(0);
    }
}
