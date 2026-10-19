package cubes.server;

import cubes.cnn.ChunkExecutor;
import cubes.cnn.Cnn;
import cubes.cnn.Dataset;
import cubes.cnn.Params;
import cubes.cnn.Sgd;
import cubes.net.Frame;
import cubes.net.Msg;
import cubes.net.PayloadReader;
import cubes.net.PayloadWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entrenamiento distribuido con paralelismo de datos y servidor de parámetros.
 *
 * Por cada lote:
 *   1. Se reparte el lote entre los nodos, proporcional a la velocidad medida de cada uno.
 *   2. A cada nodo se le envían los pesos actuales (SET_WEIGHTS) y su parte (TRAIN_TASK).
 *      Los envíos van en paralelo.
 *   3. Cada nodo devuelve la suma de gradientes de sus imágenes (GRADIENTS).
 *   4. El servidor suma todo, divide entre el tamaño del lote y actualiza los pesos (SGD).
 * Si un nodo cae a mitad de un lote, su parte se reenvía a otro nodo vivo.
 */
public final class DistributedTrainer {

    public interface Progress {
        void line(String text);
    }

    public static final class Config {
        public int images = 2048;
        public int epochs = 1;
        public int batch = 64;
        public float lr = 0.01f;
        public float momentum = 0.9f;
        public String langs = "all";
        public int maxNodes = 0;
        public boolean publish = false;
        public String label = "distribuido";
        public int testImages = 1000;
    }

    public static final class Result {
        public double seconds;
        public double loss;
        public double accuracy;
        public double testAccuracy = Double.NaN;
        public int nodesUsed;
        public String detail;
    }

    private static final class Part {
        final int start, count;

        Part(int start, int count) {
            this.start = start;
            this.count = count;
        }
    }

    /** Estadísticas acumuladas por nodo durante un entrenamiento. */
    private static final class NodeStats {
        long images;
        long computeMs;
    }

    private final NodeRegistry registry;
    private final ModelStore models;
    private final Dataset train;
    private final Dataset test;
    private final File initWeights;
    private final File modelFile;
    private final File csv;
    private final Log log;
    private final AtomicInteger versions = new AtomicInteger(1000);
    private final ExecutorService sendPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "envio-entrenamiento");
        t.setDaemon(true);
        return t;
    });

    public DistributedTrainer(NodeRegistry registry, ModelStore models, Dataset train, Dataset test, File initWeights,
                              File modelFile, File csv, Log log) {
        this.registry = registry;
        this.models = models;
        this.train = train;
        this.test = test;
        this.initWeights = initWeights;
        this.modelFile = modelFile;
        this.csv = csv;
        this.log = log;
    }

    private Params initialParams() throws IOException {
        if (initWeights.exists()) {
            Params p = Params.load(initWeights);
            if (p.classes == train.numClasses()) {
                return p;
            }
        }
        Params p = Params.randomInit(train.numClasses(), 42L);
        p.save(initWeights);
        return p;
    }

    /** Reparte n imágenes entre los nodos de forma proporcional a su velocidad (mayor resto). */
    static int[] split(int n, List<NodeHandle> nodes) {
        double total = 0;
        for (NodeHandle h : nodes) {
            total += Math.max(1e-6, h.speed);
        }
        int[] counts = new int[nodes.size()];
        double[] rest = new double[nodes.size()];
        int assigned = 0;
        for (int i = 0; i < counts.length; i++) {
            double exact = n * Math.max(1e-6, nodes.get(i).speed) / total;
            counts[i] = (int) Math.floor(exact);
            rest[i] = exact - counts[i];
            assigned += counts[i];
        }
        while (assigned < n) {
            int best = 0;
            for (int i = 1; i < rest.length; i++) {
                if (rest[i] > rest[best]) {
                    best = i;
                }
            }
            counts[best]++;
            rest[best] = -1;
            assigned++;
        }
        return counts;
    }

    private byte[] trainPayload(int version, int start, int count) {
        PayloadWriter w = new PayloadWriter(count * Dataset.RECORD + 16).u8(Msg.SLOT_TRAINING).i32(version).i32(count);
        for (int i = 0; i < count; i++) {
            w.u8(train.label(start + i));
        }
        for (int i = 0; i < count; i++) {
            w.bytes(train.raw, train.pixelOffset(start + i), cubes.cnn.Params.IN_SIZE);
        }
        return w.toBytes();
    }

    /** Envía pesos + tarea a un nodo en un hilo aparte y devuelve el futuro con la respuesta. */
    private CompletableFuture<Frame> dispatch(final NodeHandle node, final int version, final float[] weights,
                                              final Part part) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                node.setWeights(Msg.SLOT_TRAINING, version, weights);
                return node.request(Msg.TRAIN_TASK, trainPayload(version, part.start, part.count))
                        .get(600, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, sendPool);
    }

    public synchronized Result run(Config cfg, Progress progress) throws Exception {
        List<NodeHandle> nodes = registry.select(cfg.langs, cfg.maxNodes);
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No hay nodos conectados que cumplan el filtro '" + cfg.langs + "'");
        }
        int images = Math.min(cfg.images, train.size);
        Params params = initialParams();
        Sgd sgd = new Sgd(cfg.lr, cfg.momentum);
        float[] grad = new float[params.data.length];
        Map<NodeHandle, NodeStats> stats = new LinkedHashMap<NodeHandle, NodeStats>();
        for (NodeHandle n : nodes) {
            stats.put(n, new NodeStats());
        }
        StringBuilder used = new StringBuilder();
        for (NodeHandle n : nodes) {
            used.append(used.length() == 0 ? "" : ", ").append(n.lang).append("@").append(n.hostname).append("x").append(n.threads);
        }
        progress.line(String.format(Locale.ROOT, "Entrenamiento distribuido: %d nodos [%s], %d imagenes, %d epocas, lote %d",
                nodes.size(), used, images, cfg.epochs, cfg.batch));

        long t0 = System.nanoTime();
        double lossSum = 0;
        long correct = 0, count = 0;
        int batches = (images + cfg.batch - 1) / cfg.batch;
        for (int e = 1; e <= cfg.epochs; e++) {
            double eLoss = 0;
            long eCorrect = 0, eCount = 0;
            long te = System.nanoTime();
            for (int b = 0; b < batches; b++) {
                int start = b * cfg.batch;
                int n = Math.min(cfg.batch, images - start);
                List<NodeHandle> live = new ArrayList<NodeHandle>();
                for (NodeHandle h : nodes) {
                    if (h.alive) {
                        live.add(h);
                    }
                }
                if (live.isEmpty()) {
                    throw new IllegalStateException("Se cayeron todos los nodos del entrenamiento");
                }
                int version = versions.incrementAndGet();
                int[] counts = split(n, live);
                List<CompletableFuture<Frame>> futures = new ArrayList<CompletableFuture<Frame>>();
                List<NodeHandle> owners = new ArrayList<NodeHandle>();
                List<Part> parts = new ArrayList<Part>();
                int off = start;
                for (int i = 0; i < live.size(); i++) {
                    if (counts[i] == 0) {
                        continue;
                    }
                    Part part = new Part(off, counts[i]);
                    off += counts[i];
                    parts.add(part);
                    owners.add(live.get(i));
                    futures.add(dispatch(live.get(i), version, params.data, part));
                }
                java.util.Arrays.fill(grad, 0f);
                for (int i = 0; i < futures.size(); i++) {
                    Part part = parts.get(i);
                    NodeHandle owner = owners.get(i);
                    Frame f = null;
                    int attempts = 0;
                    while (f == null) {
                        try {
                            f = futures.get(i).get();
                            if (f.type == Msg.ERROR) {
                                throw new IllegalStateException("nodo " + owner.id + ": " + f.reader().str());
                            }
                        } catch (Exception ex) {
                            f = null;
                            if (++attempts > 5) {
                                throw new IllegalStateException("No se pudo completar una parte del lote", ex);
                            }
                            NodeHandle other = fastestAlive(nodes);
                            if (other == null) {
                                throw new IllegalStateException("Se cayeron todos los nodos del entrenamiento");
                            }
                            progress.line("  nodo " + owner.id + " fallo (" + rootMessage(ex) + "); se reasignan "
                                    + part.count + " imagenes al nodo " + other.id);
                            owner = other;
                            futures.set(i, dispatch(other, version, params.data, part));
                        }
                    }
                    PayloadReader r = f.reader();
                    r.i32(); // versión
                    int rn = r.i32();
                    double ls = r.f64();
                    int ok = r.i32();
                    int ms = r.i32();
                    float[] g = r.floats();
                    for (int k = 0; k < g.length; k++) {
                        grad[k] += g[k];
                    }
                    eLoss += ls;
                    eCorrect += ok;
                    eCount += rn;
                    owner.updateSpeed(rn, ms);
                    owner.imagesTrained.addAndGet(rn);
                    NodeStats ns = stats.get(owner);
                    if (ns == null) {
                        ns = new NodeStats();
                        stats.put(owner, ns);
                    }
                    ns.images += rn;
                    ns.computeMs += ms;
                }
                sgd.step(params, grad, n);
                if (batches >= 10 && (b + 1) % Math.max(1, batches / 10) == 0) {
                    progress.line(String.format(Locale.ROOT, "  epoca %d: lote %d/%d, perdida %.4f", e, b + 1, batches,
                            eLoss / Math.max(1, eCount)));
                }
            }
            double sec = (System.nanoTime() - te) / 1e9;
            progress.line(String.format(Locale.ROOT, "epoca %d: %.2f s, %.1f img/s, perdida %.4f, acierto %.2f%%", e, sec,
                    images / sec, eLoss / eCount, 100.0 * eCorrect / eCount));
            lossSum += eLoss;
            correct += eCorrect;
            count += eCount;
        }
        Result res = new Result();
        res.seconds = (System.nanoTime() - t0) / 1e9;
        res.loss = lossSum / count;
        res.accuracy = (double) correct / count;
        res.nodesUsed = nodes.size();

        StringBuilder detail = new StringBuilder();
        for (Map.Entry<NodeHandle, NodeStats> en : stats.entrySet()) {
            NodeHandle h = en.getKey();
            NodeStats s = en.getValue();
            detail.append(String.format(Locale.ROOT, "nodo %d [%s] %s hilos=%d: %d imagenes, %.2f s de computo%n",
                    h.id, h.lang, h.hostname, h.threads, s.images, s.computeMs / 1000.0));
        }
        if (test != null && cfg.testImages > 0) {
            try {
                // al publicar se evalúa con todo el conjunto de prueba; en las mediciones, con una parte
                int nTest = cfg.publish ? test.size : Math.min(cfg.testImages, test.size);
                res.testAccuracy = evaluate(nodes, params, nTest);
                progress.line(String.format(Locale.ROOT, "acierto en prueba: %.2f%%", 100 * res.testAccuracy));
            } catch (Exception ex) {
                progress.line("no se pudo evaluar en prueba: " + rootMessage(ex));
            }
        }
        res.detail = detail.toString();
        if (cfg.publish) {
            models.publish(params.copy());
            File backup = new File(modelFile.getAbsoluteFile().getParentFile(), "model_anterior.bin");
            if (modelFile.exists()) {
                java.nio.file.Files.copy(modelFile.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            params.save(modelFile);
            progress.line("Modelo publicado para el chat y guardado en " + modelFile
                    + (backup.exists() ? " (el anterior quedo en " + backup.getName() + ")" : ""));
        }
        if (!cfg.publish) {
            // solo las corridas de medición van a distribuido.csv (y a las gráficas)
            appendCsv(cfg, nodes, images, res);
        }
        return res;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static NodeHandle fastestAlive(List<NodeHandle> nodes) {
        NodeHandle best = null;
        for (NodeHandle h : nodes) {
            if (h.alive && (best == null || h.speed > best.speed)) {
                best = h;
            }
        }
        return best;
    }

    /** Evalúa los pesos repartiendo el conjunto de prueba entre los nodos (INFER_TASK, slot 1). */
    private double evaluate(List<NodeHandle> nodes, Params params, int total) throws Exception {
        List<NodeHandle> live = new ArrayList<NodeHandle>();
        for (NodeHandle h : nodes) {
            if (h.alive) {
                live.add(h);
            }
        }
        int version = versions.incrementAndGet();
        int[] counts = split(total, live);
        List<CompletableFuture<Frame>> futures = new ArrayList<CompletableFuture<Frame>>();
        List<Integer> starts = new ArrayList<Integer>();
        int off = 0;
        for (int i = 0; i < live.size(); i++) {
            if (counts[i] == 0) {
                continue;
            }
            final NodeHandle node = live.get(i);
            final int s = off, c = counts[i];
            off += c;
            starts.add(s);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    node.setWeights(Msg.SLOT_TRAINING, version, params.data);
                    PayloadWriter w = new PayloadWriter(c * 3072 + 16).u8(Msg.SLOT_TRAINING).i32(version).i32(c);
                    for (int k = 0; k < c; k++) {
                        w.bytes(test.raw, test.pixelOffset(s + k), cubes.cnn.Params.IN_SIZE);
                    }
                    return node.request(Msg.INFER_TASK, w.toBytes()).get(600, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, sendPool));
        }
        int ok = 0;
        for (int i = 0; i < futures.size(); i++) {
            Frame f = futures.get(i).get();
            PayloadReader r = f.reader();
            int n = r.i32();
            int k = r.i32();
            float[] probs = r.floats(n * k);
            for (int j = 0; j < n; j++) {
                int best = 0;
                for (int q = 1; q < k; q++) {
                    if (probs[j * k + q] > probs[j * k + best]) {
                        best = q;
                    }
                }
                if (best == test.label(starts.get(i) + j)) {
                    ok++;
                }
            }
        }
        return (double) ok / total;
    }

    private void appendCsv(Config cfg, List<NodeHandle> nodes, int images, Result res) {
        if (csv == null) {
            return;
        }
        Set<String> langs = new LinkedHashSet<String>();
        int threads = 0;
        for (NodeHandle h : nodes) {
            langs.add(h.lang);
            threads = Math.max(threads, h.threads);
        }
        StringBuilder l = new StringBuilder();
        for (String s : langs) {
            l.append(l.length() == 0 ? "" : "+").append(s);
        }
        try {
            boolean header = !csv.exists();
            if (csv.getAbsoluteFile().getParentFile() != null) {
                csv.getAbsoluteFile().getParentFile().mkdirs();
            }
            PrintWriter w = new PrintWriter(new FileWriter(csv, true));
            try {
                if (header) {
                    w.println("escenario,lenguaje,maquinas,hilos,imagenes,epocas,lote,segundos,img_por_s,perdida,acierto_train,acierto_test");
                }
                w.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%.3f,%.2f,%.4f,%.4f,%.4f%n", cfg.label, l, nodes.size(), threads,
                        images, cfg.epochs, cfg.batch, res.seconds, (double) images * cfg.epochs / res.seconds, res.loss,
                        res.accuracy, res.testAccuracy);
            } finally {
                w.close();
            }
        } catch (IOException e) {
            log.info("No se pudo escribir " + csv + ": " + e.getMessage());
        }
    }

    /**
     * Prueba cruzada: manda las mismas 8 imágenes y los mismos pesos a cada nodo y compara
     * sus gradientes y probabilidades con el cálculo local en Java.
     */
    public synchronized String crossCheck() throws Exception {
        Params p = initialParams();
        int n = Math.min(8, train.size);
        float[][] x = new float[n][];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = train.image(i);
            y[i] = train.label(i);
        }
        Cnn cnn = new Cnn(new ChunkExecutor(1));
        float[] ref = new float[p.data.length];
        Cnn.Stats st = cnn.forwardBackward(p, x, y, ref);
        float[][] refProbs = cnn.predict(p, x);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Referencia Java local: perdida=%.6f aciertos=%d%n", st.lossSum, st.correct));
        List<NodeHandle> all = registry.alive();
        if (all.isEmpty()) {
            sb.append("No hay nodos conectados.\n");
        }
        for (NodeHandle h : all) {
            int version = versions.incrementAndGet();
            try {
                h.setWeights(Msg.SLOT_TRAINING, version, p.data);
                Frame f = h.request(Msg.TRAIN_TASK, trainPayload(version, 0, n)).get(120, TimeUnit.SECONDS);
                if (f.type == Msg.ERROR) {
                    sb.append("nodo ").append(h.id).append(" ERROR: ").append(f.reader().str()).append('\n');
                    continue;
                }
                PayloadReader r = f.reader();
                r.i32();
                r.i32();
                double ls = r.f64();
                int ok = r.i32();
                r.i32();
                float[] g = r.floats();
                double maxErr = 0;
                for (int k = 0; k < g.length; k++) {
                    maxErr = Math.max(maxErr, Math.abs(g[k] - ref[k]) / (1e-3 + 1e-3 * Math.abs(ref[k])));
                }
                PayloadWriter w = new PayloadWriter(n * 3072 + 16).u8(Msg.SLOT_TRAINING).i32(version).i32(n);
                for (int i = 0; i < n; i++) {
                    w.bytes(train.raw, train.pixelOffset(i), cubes.cnn.Params.IN_SIZE);
                }
                Frame fi = h.request(Msg.INFER_TASK, w.toBytes()).get(120, TimeUnit.SECONDS);
                PayloadReader ri = fi.reader();
                int rn = ri.i32();
                int k = ri.i32();
                float[] probs = ri.floats(rn * k);
                double maxP = 0;
                for (int i = 0; i < rn; i++) {
                    for (int q = 0; q < k; q++) {
                        maxP = Math.max(maxP, Math.abs(probs[i * k + q] - refProbs[i][q]));
                    }
                }
                boolean pass = maxErr <= 1.0 && maxP <= 1e-3 && ok == st.correct && Math.abs(ls - st.lossSum) < 1e-2;
                sb.append(String.format(Locale.ROOT,
                        "nodo %d [%s] %s: perdida=%.6f aciertos=%d err_grad=%.3f (<=1 ok) err_prob=%.2e -> %s%n",
                        h.id, h.lang, h.hostname, ls, ok, maxErr, maxP, pass ? "OK" : "DIFIERE"));
            } catch (Exception e) {
                sb.append("nodo ").append(h.id).append(" fallo: ").append(rootMessage(e)).append('\n');
            }
        }
        return sb.toString();
    }
}
