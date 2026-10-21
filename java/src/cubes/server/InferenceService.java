package cubes.server;

import cubes.cnn.ChunkExecutor;
import cubes.cnn.Cnn;
import cubes.cnn.Params;
import cubes.net.Frame;
import cubes.net.Msg;
import cubes.net.PayloadReader;
import cubes.net.PayloadWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atiende las consultas "Identificar" de los clientes.
 *
 * Las peticiones entran a una cola. Cada nodo tiene SLOTS hilos que "jalan" de la cola
 * lotes de hasta MAX_BATCH imágenes y se los envían a su nodo. Así el reparto se balancea
 * solo: un nodo rápido vuelve antes por más trabajo. Si un nodo cae, sus imágenes
 * vuelven al frente de la cola y las toma otro nodo. Si no hay ningún nodo conectado,
 * el propio servidor calcula la respuesta (respaldo local).
 */
public final class InferenceService implements NodeRegistry.Listener {

    public static final class Prediction {
        public int classIndex;
        public float confidence;
        public int nodeId;
        public String nodeLang;
    }

    static final class Request {
        final byte[] pixels;
        final CompletableFuture<Prediction> result = new CompletableFuture<Prediction>();

        Request(byte[] pixels) {
            this.pixels = pixels;
        }
    }

    private static final int SLOTS = 2;
    private static final int MAX_BATCH = 16;
    private static final int QUEUE_CAPACITY = 20000;

    private final LinkedBlockingDeque<Request> queue = new LinkedBlockingDeque<Request>(QUEUE_CAPACITY);
    private final ModelStore models;
    private final NodeRegistry registry;
    private final Log log;
    final AtomicLong served = new AtomicLong();
    final AtomicLong servedLocal = new AtomicLong();
    final AtomicLong rejected = new AtomicLong();

    public InferenceService(ModelStore models, NodeRegistry registry, Log log) {
        this.models = models;
        this.registry = registry;
        this.log = log;
        registry.addListener(this);
        Thread local = new Thread(this::localFallbackLoop, "inferencia-local");
        local.setDaemon(true);
        local.start();
    }

    public int queueSize() {
        return queue.size();
    }

    /** Encola una imagen (3072 bytes CIFAR). Si la cola está llena, falla con "servidor saturado". */
    public CompletableFuture<Prediction> submit(byte[] pixels) {
        Request r = new Request(pixels);
        if (!queue.offerLast(r)) {
            rejected.incrementAndGet();
            r.result.completeExceptionally(new IllegalStateException("servidor saturado"));
        }
        return r.result;
    }

    @Override
    public void nodeAdded(final NodeHandle node) {
        for (int i = 0; i < SLOTS; i++) {
            Thread t = new Thread(() -> nodeLoop(node), "infer-nodo" + node.id + "-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    private List<Request> takeBatch(long waitMs) throws InterruptedException {
        Request first = queue.pollFirst(waitMs, TimeUnit.MILLISECONDS);
        List<Request> batch = new ArrayList<Request>(MAX_BATCH);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        queue.drainTo(batch, MAX_BATCH - 1);
        return batch;
    }

    private void requeue(List<Request> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            if (!queue.offerFirst(batch.get(i))) {
                batch.get(i).result.completeExceptionally(new IllegalStateException("servidor saturado"));
            }
        }
    }

    private void nodeLoop(NodeHandle node) {
        while (node.alive) {
            List<Request> batch;
            try {
                batch = takeBatch(500);
            } catch (InterruptedException e) {
                return;
            }
            if (batch.isEmpty()) {
                continue;
            }
            if (!node.alive) {
                requeue(batch);
                return;
            }
            try {
                ModelStore.Snapshot snap;
                synchronized (node.servingLock) {
                    // La foto del modelo se toma dentro del candado: así el otro hilo de este nodo
                    // no puede reemplazar una versión nueva por una vieja.
                    snap = models.snapshot();
                    if (node.servingVersion != snap.version) {
                        node.setWeights(Msg.SLOT_SERVING, snap.version, snap.params.data);
                        node.servingVersion = snap.version;
                    }
                }
                int n = batch.size();
                PayloadWriter w = new PayloadWriter(n * 3072 + 16).u8(Msg.SLOT_SERVING).i32(snap.version).i32(n);
                for (Request r : batch) {
                    w.bytes(r.pixels, 0, r.pixels.length);
                }
                Frame resp = node.request(Msg.INFER_TASK, w.toBytes()).get(60, TimeUnit.SECONDS);
                PayloadReader r = resp.reader();
                if (resp.type == Msg.ERROR) {
                    String msg = r.str();
                    log.info("Nodo " + node.id + " respondio ERROR en inferencia: " + msg);
                    if ("version".equals(msg)) {
                        synchronized (node.servingLock) {
                            node.servingVersion = -1;
                        }
                        requeue(batch);
                    } else {
                        // Otro error no se arregla reintentando en el mismo nodo: se informa al cliente.
                        for (Request q : batch) {
                            q.result.completeExceptionally(new IllegalStateException("nodo " + node.id + ": " + msg));
                        }
                    }
                    continue;
                }
                int rn = r.i32();
                int k = r.i32();
                float[] probs = r.floats(rn * k);
                int ms = r.i32();
                node.updateSpeed(rn, ms);
                node.imagesInferred.addAndGet(rn);
                for (int i = 0; i < rn && i < n; i++) {
                    float[] p = new float[k];
                    System.arraycopy(probs, i * k, p, 0, k);
                    batch.get(i).result.complete(toPrediction(p, node.id, node.lang));
                }
                served.addAndGet(Math.min(rn, n));
                if (rn < n) {
                    requeue(new ArrayList<Request>(batch.subList(rn, n)));
                }
            } catch (Exception e) {
                log.info("Fallo de inferencia en nodo " + node.id + " (" + e + "): se reasigna el lote");
                requeue(batch);
                if (!node.alive) {
                    return;
                }
            }
        }
    }

    private static Prediction toPrediction(float[] probs, int nodeId, String lang) {
        Prediction p = new Prediction();
        p.classIndex = Cnn.argmax(probs);
        p.confidence = probs[p.classIndex];
        p.nodeId = nodeId;
        p.nodeLang = lang;
        return p;
    }

    /** Respaldo: si no hay nodos conectados, el servidor calcula con su propia CNN en Java. */
    private void localFallbackLoop() {
        ChunkExecutor ex = new ChunkExecutor(Runtime.getRuntime().availableProcessors());
        Cnn cnn = new Cnn(ex);
        while (true) {
            try {
                if (!registry.alive().isEmpty()) {
                    Thread.sleep(200);
                    continue;
                }
                List<Request> batch = takeBatch(200);
                if (batch.isEmpty()) {
                    continue;
                }
                if (!registry.alive().isEmpty()) {
                    requeue(batch);
                    continue;
                }
                Params p = models.snapshot().params;
                float[][] x = new float[batch.size()][];
                for (int i = 0; i < x.length; i++) {
                    x[i] = Cnn.normalize(batch.get(i).pixels, 0);
                }
                float[][] probs = cnn.predict(p, x);
                for (int i = 0; i < x.length; i++) {
                    batch.get(i).result.complete(toPrediction(probs[i], 0, "java-servidor"));
                }
                served.addAndGet(x.length);
                servedLocal.addAndGet(x.length);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.info("Error en inferencia local: " + e);
            }
        }
    }
}
