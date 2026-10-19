package cubes.bench;

import cubes.cnn.ChunkExecutor;
import cubes.cnn.Cnn;
import cubes.cnn.Dataset;
import cubes.cnn.Params;
import cubes.cnn.Sgd;
import cubes.tools.Args;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * Entrenamiento local en un solo proceso Java: escenarios "secuencial" (--threads 1)
 * y "paralelo" (--threads H). No usa red.
 *
 * Ejemplo:
 *   java -cp out cubes.bench.LocalTrain --train data/train.bin --test data/test.bin
 *        --threads 4 --images 2048 --epochs 1 --batch 64 --save data/model.bin
 */
public final class LocalTrain {

    public static void main(String[] a) throws IOException {
        Args args = new Args(a);
        Dataset train = Dataset.load(new File(args.get("train", "data/train.bin")));
        String testPath = args.get("test", "data/test.bin");
        Dataset test = new File(testPath).exists() ? Dataset.load(new File(testPath)) : null;
        int threads = args.getInt("threads", 1);
        int images = Math.min(args.getInt("images", train.size), train.size);
        int epochs = args.getInt("epochs", 1);
        int batch = args.getInt("batch", 64);
        float lr = args.getFloat("lr", 0.01f);
        float momentum = args.getFloat("momentum", 0.9f);
        int testImages = test == null ? 0 : Math.min(args.getInt("test-images", test.size), test.size);

        Params params = loadOrInit(args.get("init", "data/init_weights.bin"), train.numClasses());
        Sgd sgd = new Sgd(lr, momentum);

        System.out.printf(Locale.ROOT, "LocalTrain: hilos=%d imagenes=%d epocas=%d lote=%d clases=%d%n",
                threads, images, epochs, batch, params.classes);
        ChunkExecutor ex = new ChunkExecutor(threads);
        Cnn cnn = new Cnn(ex);
        // calentamiento: un lote sin medir ni actualizar pesos, para que el JIT compile el código caliente
        // y la primera medición no salga inflada (Go y Python no lo necesitan)
        int warm = Math.min(args.getInt("warmup", batch), images);
        if (warm > 0) {
            float[][] wx = new float[warm][];
            int[] wy = new int[warm];
            for (int i = 0; i < warm; i++) {
                wx[i] = train.image(i);
                wy[i] = train.label(i);
            }
            cnn.forwardBackward(params, wx, wy, new float[params.data.length]);
        }
        long t0 = System.nanoTime();
        Cnn.Stats last = null;
        for (int e = 1; e <= epochs; e++) {
            long te = System.nanoTime();
            Cnn.Stats st = new Cnn.Stats();
            float[] grad = new float[params.data.length];
            for (int start = 0; start < images; start += batch) {
                int n = Math.min(batch, images - start);
                float[][] x = new float[n][];
                int[] y = new int[n];
                for (int i = 0; i < n; i++) {
                    x[i] = train.image(start + i);
                    y[i] = train.label(start + i);
                }
                java.util.Arrays.fill(grad, 0f);
                st.add(cnn.forwardBackward(params, x, y, grad));
                sgd.step(params, grad, n);
            }
            double sec = (System.nanoTime() - te) / 1e9;
            System.out.printf(Locale.ROOT, "  epoca %d: %.2f s, %.1f img/s, perdida %.4f, acierto entrenamiento %.2f%%%n",
                    e, sec, images / sec, st.lossSum / st.count, 100.0 * st.correct / st.count);
            last = st;
        }
        double trainSec = (System.nanoTime() - t0) / 1e9;

        double testAcc = Double.NaN;
        if (testImages > 0) {
            testAcc = evaluate(cnn, params, test, testImages, 256);
            System.out.printf(Locale.ROOT, "  acierto en prueba (%d imágenes): %.2f%%%n", testImages, 100 * testAcc);
        }
        ex.close();

        if (args.has("save")) {
            params.save(new File(args.get("save", "data/model.bin")));
            System.out.println("  pesos guardados en " + args.get("save", ""));
        }
        if (args.has("csv")) {
            appendCsv(new File(args.get("csv", "")), args.get("label", threads == 1 ? "secuencial" : "paralelo"),
                    threads, images, epochs, batch, trainSec, last, testAcc);
        }
        System.out.printf(Locale.ROOT, "TOTAL %.3f s%n", trainSec);
    }

    static Params loadOrInit(String path, int classes) throws IOException {
        File f = new File(path);
        if (f.exists()) {
            Params p = Params.load(f);
            if (p.classes == classes) {
                return p;
            }
        }
        Params p = Params.randomInit(classes, 42L);
        if (f.getAbsoluteFile().getParentFile() != null) {
            f.getAbsoluteFile().getParentFile().mkdirs();
        }
        p.save(f);
        System.out.println("  pesos iniciales creados en " + f + " (semilla 42)");
        return p;
    }

    public static double evaluate(Cnn cnn, Params params, Dataset ds, int count, int batch) {
        int correct = 0;
        for (int start = 0; start < count; start += batch) {
            int n = Math.min(batch, count - start);
            float[][] x = new float[n][];
            for (int i = 0; i < n; i++) {
                x[i] = ds.image(start + i);
            }
            float[][] probs = cnn.predict(params, x);
            for (int i = 0; i < n; i++) {
                if (Cnn.argmax(probs[i]) == ds.label(start + i)) {
                    correct++;
                }
            }
        }
        return (double) correct / count;
    }

    static void appendCsv(File f, String label, int threads, int images, int epochs, int batch, double sec,
                          Cnn.Stats st, double testAcc) throws IOException {
        boolean header = !f.exists();
        if (f.getAbsoluteFile().getParentFile() != null) {
            f.getAbsoluteFile().getParentFile().mkdirs();
        }
        PrintWriter w = new PrintWriter(new FileWriter(f, true));
        try {
            if (header) {
                w.println("escenario,lenguaje,maquinas,hilos,imagenes,epocas,lote,segundos,img_por_s,perdida,acierto_train,acierto_test");
            }
            w.printf(Locale.ROOT, "%s,java,1,%d,%d,%d,%d,%.3f,%.2f,%.4f,%.4f,%.4f%n", label, threads, images, epochs,
                    batch, sec, (double) images * epochs / sec, st.lossSum / st.count,
                    (double) st.correct / st.count, testAcc);
        } finally {
            w.close();
        }
    }
}
