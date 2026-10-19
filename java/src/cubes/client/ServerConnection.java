package cubes.client;

import cubes.net.Frame;
import cubes.net.Msg;
import cubes.net.PayloadReader;
import cubes.net.PayloadWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Conexión de un cliente con el servidor central (puerto 5000). Una petición a la vez:
 * envía la trama y espera la respuesta con el mismo id.
 */
public final class ServerConnection implements Closeable {

    public static final class ServerError extends IOException {
        public ServerError(String msg) {
            super(msg);
        }
    }

    public static final class LoginInfo {
        public int idClientChat;
        public List<String> classes = new ArrayList<String>();
    }

    public static final class Result {
        public int classIndex;
        public String label;
        public float confidence;
        public int nodeId;
        public String nodeLang;
        public int serverMillis;
    }

    public interface ProgressListener {
        void line(String text);
    }

    /** Recibe cada resultado de un lote apenas llega (error != null si esa imagen falló). */
    public interface BatchListener {
        void item(int index, Result result, String error);
    }

    public static final class BatchSummary {
        public int images;
        public int ok;
        public int serverMillis;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private int nextId = 1;

    public ServerConnection(String host, int port) throws IOException {
        this(host, port, 10000);
    }

    public ServerConnection(String host, int port, int connectTimeoutMs) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));
    }

    private int send(int type, byte[] payload) throws IOException {
        int id = nextId++;
        new Frame(type, id, payload).write(out);
        return id;
    }

    private Frame expect(int id, int type) throws IOException {
        while (true) {
            Frame f = Frame.read(in);
            if (f.requestId != id) {
                continue;
            }
            if (f.type == Msg.ERROR) {
                throw new ServerError(f.reader().str());
            }
            if (f.type != type) {
                throw new IOException("Respuesta inesperada: " + Msg.name(f.type));
            }
            return f;
        }
    }

    public synchronized LoginInfo login(String user) throws IOException {
        int id = send(Msg.LOGIN, new PayloadWriter().str(user).toBytes());
        PayloadReader r = expect(id, Msg.LOGIN_OK).reader();
        LoginInfo info = new LoginInfo();
        info.idClientChat = r.i32();
        int k = r.i32();
        for (int i = 0; i < k; i++) {
            info.classes.add(r.str());
        }
        return info;
    }

    /** Envía una imagen: formato 0 = 3072 bytes CIFAR, formato 1 = archivo JPG/PNG. */
    public synchronized Result identify(int format, byte[] data) throws IOException {
        int id = send(Msg.IDENTIFY, new PayloadWriter(data.length + 16).u8(format).i32(data.length)
                .bytes(data, 0, data.length).toBytes());
        PayloadReader r = expect(id, Msg.RESULT).reader();
        Result res = new Result();
        res.classIndex = r.i32();
        res.label = r.str();
        res.confidence = r.f32();
        res.nodeId = r.i32();
        res.nodeLang = r.str();
        res.serverMillis = r.i32();
        return res;
    }

    /**
     * Envía muchas imágenes en una sola petición (cada una ya reducida a 3072 bytes CIFAR).
     * El servidor las encola todas juntas, así que se reparten entre todos los nodos.
     */
    public synchronized BatchSummary identifyBatch(List<byte[]> images, BatchListener listener) throws IOException {
        PayloadWriter w = new PayloadWriter(images.size() * 3072 + 8).i32(images.size());
        for (byte[] px : images) {
            w.bytes(px, 0, px.length);
        }
        int id = send(Msg.IDENTIFY_BATCH, w.toBytes());
        while (true) {
            Frame f = Frame.read(in);
            if (f.requestId != id) {
                continue;
            }
            PayloadReader r = f.reader();
            if (f.type == Msg.RESULT_ITEM) {
                int index = r.i32();
                if (r.u8() == 1) {
                    Result res = new Result();
                    res.classIndex = r.i32();
                    res.label = r.str();
                    res.confidence = r.f32();
                    res.nodeId = r.i32();
                    res.nodeLang = r.str();
                    res.serverMillis = r.i32();
                    listener.item(index, res, null);
                } else {
                    listener.item(index, null, r.str());
                }
            } else if (f.type == Msg.BATCH_DONE) {
                BatchSummary sum = new BatchSummary();
                sum.images = r.i32();
                sum.ok = r.i32();
                sum.serverMillis = r.i32();
                return sum;
            } else if (f.type == Msg.ERROR) {
                throw new ServerError(r.str());
            }
        }
    }

    public synchronized List<String> history() throws IOException {
        int id = send(Msg.HISTORY_REQ, null);
        PayloadReader r = expect(id, Msg.HISTORY).reader();
        int n = r.i32();
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            out.add(r.str());
        }
        return out;
    }

    public synchronized String status() throws IOException {
        int id = send(Msg.STATUS_REQ, null);
        return expect(id, Msg.STATUS).reader().str();
    }

    public synchronized String crossCheck() throws IOException {
        int id = send(Msg.CROSSCHECK_REQ, null);
        return expect(id, Msg.CROSSCHECK).reader().str();
    }

    /** Pide un entrenamiento distribuido; muestra el progreso y devuelve el resumen final. */
    public synchronized String train(int images, int epochs, int batch, float lr, String langs, int maxNodes,
                                     boolean publish, String label, ProgressListener progress) throws IOException {
        int id = send(Msg.TRAIN_REQ, new PayloadWriter().i32(images).i32(epochs).i32(batch).f32(lr).str(langs)
                .i32(maxNodes).u8(publish ? 1 : 0).str(label).toBytes());
        while (true) {
            Frame f = Frame.read(in);
            if (f.requestId != id) {
                continue;
            }
            PayloadReader r = f.reader();
            if (f.type == Msg.TRAIN_PROGRESS) {
                progress.line(r.str());
            } else if (f.type == Msg.TRAIN_DONE) {
                double sec = r.f64();
                double loss = r.f64();
                double acc = r.f64();
                int nodes = r.i32();
                String detail = r.str();
                return String.format(java.util.Locale.ROOT,
                        "TOTAL %.3f s | nodos %d | perdida %.4f | acierto entrenamiento %.2f%%%n%s", sec, nodes, loss,
                        100 * acc, detail);
            } else if (f.type == Msg.ERROR) {
                throw new ServerError(r.str());
            }
        }
    }

    @Override
    public void close() {
        try {
            new Frame(Msg.BYE, 0, null).write(out);
        } catch (IOException ignored) {
            // nada
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // nada
        }
    }
}
