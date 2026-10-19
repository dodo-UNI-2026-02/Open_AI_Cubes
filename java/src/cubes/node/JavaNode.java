package cubes.node;

import cubes.cnn.ChunkExecutor;
import cubes.cnn.Cnn;
import cubes.cnn.Params;
import cubes.net.Frame;
import cubes.net.Msg;
import cubes.net.PayloadReader;
import cubes.net.PayloadWriter;
import cubes.tools.Args;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Nodo de cómputo en Java (Caso 1: LP1 = LP2).
 *
 * Se conecta al servidor central, se registra y procesa en orden los mensajes que llegan:
 * pesos (SET_WEIGHTS), tareas de entrenamiento (TRAIN_TASK → GRADIENTS) y de inferencia
 * (INFER_TASK → INFER_RESULT). Cada tarea se calcula con H hilos usando los micro chunks.
 * Un hilo aparte envía HEARTBEAT cada 2 s para que el servidor sepa que sigue vivo.
 *
 * Uso: java -cp out cubes.node.JavaNode --server 127.0.0.1 --port 6000 --threads 4
 */
public final class JavaNode {

    private final String host;
    private final int port;
    private final int threads;
    private final Cnn cnn;
    private final Params[] slots = new Params[2];
    private final int[] versions = {-1, -1};
    private volatile DataOutputStream out;
    private final Object sendLock = new Object();

    JavaNode(String host, int port, int threads) {
        this.host = host;
        this.port = port;
        this.threads = threads;
        this.cnn = new Cnn(new ChunkExecutor(threads));
    }

    private void send(int type, int id, byte[] payload) throws IOException {
        synchronized (sendLock) {
            new Frame(type, id, payload).write(out);
        }
    }

    void runForever() {
        while (true) {
            try {
                session();
            } catch (IOException e) {
                System.out.println("Conexion con el servidor perdida o rechazada: " + e.getMessage());
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("Reintentando conexion con " + host + ":" + port + " ...");
        }
    }

    private void session() throws IOException {
        Socket s = new Socket(host, port);
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
        versions[0] = versions[1] = -1;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            hostname = "desconocido";
        }
        send(Msg.REGISTER, 1, new PayloadWriter().str("java").i32(Runtime.getRuntime().availableProcessors())
                .i32(threads).str(hostname).toBytes());
        Frame ok = Frame.read(in);
        if (ok.type != Msg.REGISTER_OK) {
            s.close();
            throw new IOException("registro rechazado");
        }
        int nodeId = ok.reader().i32();
        System.out.println("Registrado como nodo " + nodeId + " en " + host + ":" + port + " con " + threads + " hilos");

        final Socket sock = s;
        Thread hb = new Thread(() -> {
            while (!sock.isClosed()) {
                try {
                    Thread.sleep(2000);
                    send(Msg.HEARTBEAT, 0, new PayloadWriter().i32(0).toBytes());
                } catch (Exception e) {
                    return;
                }
            }
        }, "heartbeat");
        hb.setDaemon(true);
        hb.start();

        try {
            while (true) {
                Frame f = Frame.read(in);
                if (f.type == Msg.BYE) {
                    System.out.println("El servidor cerro la sesion");
                    break;
                }
                handle(f);
            }
        } finally {
            s.close();
        }
    }

    private void handle(Frame f) throws IOException {
        PayloadReader r = f.reader();
        switch (f.type) {
            case Msg.SET_WEIGHTS: {
                int slot = r.u8();
                int version = r.i32();
                slots[slot] = Params.fromArray(r.floats());
                versions[slot] = version;
                return;
            }
            case Msg.TRAIN_TASK: {
                int slot = r.u8();
                int version = r.i32();
                if (slot > 1 || versions[slot] != version) {
                    send(Msg.ERROR, f.requestId, new PayloadWriter().str("version").toBytes());
                    return;
                }
                int n = r.i32();
                byte[] labels = r.bytes(n);
                byte[] px = r.bytes(n * Params.IN_SIZE);
                long t0 = System.nanoTime();
                float[][] x = new float[n][];
                int[] y = new int[n];
                for (int i = 0; i < n; i++) {
                    x[i] = Cnn.normalize(px, i * Params.IN_SIZE);
                    y[i] = labels[i] & 0xFF;
                }
                Params p = slots[slot];
                float[] grad = new float[p.data.length];
                Cnn.Stats st = cnn.forwardBackward(p, x, y, grad);
                int ms = (int) ((System.nanoTime() - t0) / 1000000);
                send(Msg.GRADIENTS, f.requestId, new PayloadWriter(grad.length * 4 + 64).i32(version).i32(n)
                        .f64(st.lossSum).i32(st.correct).i32(ms).floats(grad).toBytes());
                return;
            }
            case Msg.INFER_TASK: {
                int slot = r.u8();
                int version = r.i32();
                if (slot > 1 || versions[slot] != version) {
                    send(Msg.ERROR, f.requestId, new PayloadWriter().str("version").toBytes());
                    return;
                }
                int n = r.i32();
                byte[] px = r.bytes(n * Params.IN_SIZE);
                long t0 = System.nanoTime();
                float[][] x = new float[n][];
                for (int i = 0; i < n; i++) {
                    x[i] = Cnn.normalize(px, i * Params.IN_SIZE);
                }
                Params p = slots[slot];
                float[][] probs = cnn.predict(p, x);
                int ms = (int) ((System.nanoTime() - t0) / 1000000);
                PayloadWriter w = new PayloadWriter(n * p.classes * 4 + 16).i32(n).i32(p.classes);
                for (float[] row : probs) {
                    for (float v : row) {
                        w.f32(v);
                    }
                }
                send(Msg.INFER_RESULT, f.requestId, w.i32(ms).toBytes());
                return;
            }
            default:
                send(Msg.ERROR, f.requestId, new PayloadWriter().str("mensaje no soportado").toBytes());
        }
    }

    public static void main(String[] a) {
        Args args = new Args(a);
        final JavaNode node = new JavaNode(args.get("server", "127.0.0.1"), args.getInt("port", Msg.NODE_PORT),
                args.getInt("threads", Runtime.getRuntime().availableProcessors()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (node.out != null) {
                    node.send(Msg.BYE, 0, null);
                }
            } catch (IOException ignored) {
                // nada
            }
        }));
        node.runForever();
    }
}
