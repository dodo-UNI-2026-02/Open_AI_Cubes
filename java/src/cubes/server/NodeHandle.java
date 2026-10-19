package cubes.server;

import cubes.net.Frame;
import cubes.net.Msg;
import cubes.net.PayloadWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conexión del servidor central con un nodo de cómputo.
 *
 * Un hilo lector recibe las respuestas y completa la petición pendiente que tenga el
 * mismo id. Los envíos se serializan con un candado porque varios hilos (entrenador,
 * despachador de inferencia) pueden escribir en el mismo socket.
 */
public final class NodeHandle {

    public final int id;
    public final String lang;
    public final int cores;
    public final int threads;
    public final String hostname;
    public final String address;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object sendLock = new Object();
    private final Map<Integer, CompletableFuture<Frame>> pending = new ConcurrentHashMap<Integer, CompletableFuture<Frame>>();
    private final AtomicInteger nextRequest = new AtomicInteger(1);

    final AtomicInteger inflight = new AtomicInteger();
    final AtomicLong imagesTrained = new AtomicLong();
    final AtomicLong imagesInferred = new AtomicLong();
    volatile long lastSeen = System.currentTimeMillis();
    volatile boolean alive = true;
    /** Imágenes por segundo medidas (promedio móvil). Sirve para repartir trabajo según la velocidad. */
    volatile double speed;
    /** Versión de los pesos de servicio que ya tiene este nodo (-1 = ninguna). */
    volatile int servingVersion = -1;
    final Object servingLock = new Object();

    NodeHandle(int id, Socket socket, DataInputStream in, DataOutputStream out, String lang, int cores, int threads,
               String hostname) {
        this.id = id;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.lang = lang;
        this.cores = cores;
        this.threads = threads;
        this.hostname = hostname;
        this.address = socket.getInetAddress().getHostAddress();
        // Velocidad inicial estimada: Python es mucho más lento por ser interpretado.
        this.speed = threads * ("python".equalsIgnoreCase(lang) ? 15.0 : 150.0);
    }

    static DataInputStream inputOf(Socket s) throws IOException {
        return new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
    }

    static DataOutputStream outputOf(Socket s) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
    }

    /** Envía una trama sin esperar respuesta. */
    public void send(int type, int requestId, byte[] payload) throws IOException {
        Frame f = new Frame(type, requestId, payload);
        synchronized (sendLock) {
            f.write(out);
        }
    }

    /** Envía una petición y devuelve un futuro que se completa con la respuesta del mismo id. */
    public CompletableFuture<Frame> request(int type, byte[] payload) {
        int rid = nextRequest.getAndIncrement();
        CompletableFuture<Frame> fut = new CompletableFuture<Frame>();
        if (!alive) {
            fut.completeExceptionally(new IOException("nodo " + id + " desconectado"));
            return fut;
        }
        pending.put(rid, fut);
        try {
            send(type, rid, payload);
        } catch (IOException e) {
            pending.remove(rid);
            fail(e);
            fut.completeExceptionally(e);
        }
        return fut;
    }

    public void setWeights(int slot, int version, float[] weights) throws IOException {
        send(Msg.SET_WEIGHTS, 0, new PayloadWriter(weights.length * 4 + 16).u8(slot).i32(version).floats(weights).toBytes());
    }

    /** Bucle del hilo lector: entrega cada respuesta a quien la pidió. */
    void readLoop(NodeRegistry registry) {
        try {
            while (alive) {
                Frame f = Frame.read(in);
                lastSeen = System.currentTimeMillis();
                if (f.type == Msg.HEARTBEAT) {
                    continue;
                }
                if (f.type == Msg.BYE) {
                    break;
                }
                CompletableFuture<Frame> fut = pending.remove(f.requestId);
                if (fut != null) {
                    fut.complete(f);
                }
            }
            fail(new IOException("nodo " + id + " se despidio"));
        } catch (IOException e) {
            fail(e);
        } finally {
            registry.remove(this);
        }
    }

    /** Marca el nodo como caído y hace fallar todas sus peticiones pendientes (se reasignan). */
    void fail(Exception cause) {
        if (!alive) {
            return;
        }
        alive = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // nada
        }
        for (CompletableFuture<Frame> f : pending.values()) {
            f.completeExceptionally(cause);
        }
        pending.clear();
    }

    void close() {
        try {
            send(Msg.BYE, 0, null);
        } catch (IOException ignored) {
            // nada
        }
        fail(new IOException("cerrado por el servidor"));
    }

    void updateSpeed(int images, int computeMillis) {
        if (images <= 0) {
            return;
        }
        double s = images * 1000.0 / Math.max(1, computeMillis);
        speed = 0.7 * speed + 0.3 * s;
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT, "nodo %d [%s] %s (%s) nucleos=%d hilos=%d vel=%.0f img/s entren=%d infer=%d",
                id, lang, hostname, address, cores, threads, speed, imagesTrained.get(), imagesInferred.get());
    }
}
