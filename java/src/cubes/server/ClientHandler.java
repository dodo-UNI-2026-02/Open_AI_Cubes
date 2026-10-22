package cubes.server;

import cubes.cnn.ImagePrep;
import cubes.cnn.Params;
import cubes.net.Frame;
import cubes.net.Msg;
import cubes.net.PayloadReader;
import cubes.net.PayloadWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Atiende a un cliente (chat, generador de carga o consola admin) en su propio hilo. */
final class ClientHandler implements Runnable {

    /** Máximo de imágenes en un IDENTIFY_BATCH (la cola de inferencia admite 20 000). */
    static final int MAX_BATCH_IMAGES = 5000;

    private static final String NO_TRAIN = "el servidor no tiene data/train.bin: descomprima datos_train.zip en la carpeta del proyecto "
            + "y reinicie el servidor (el chat funciona igual sin ese archivo)";

    private final Socket socket;
    private final CentralServer server;
    private DataOutputStream out;
    private Backend.Session session;

    ClientHandler(Socket socket, CentralServer server) {
        this.socket = socket;
        this.server = server;
    }

    private synchronized void send(int type, int id, byte[] payload) throws IOException {
        new Frame(type, id, payload).write(out);
    }

    private void error(int id, String msg) throws IOException {
        send(Msg.ERROR, id, new PayloadWriter().str(msg).toBytes());
    }

    @Override
    public void run() {
        server.backend.connectedClients.incrementAndGet();
        try {
            socket.setTcpNoDelay(true);
            DataInputStream in = NodeHandle.inputOf(socket);
            out = NodeHandle.outputOf(socket);
            while (true) {
                Frame f;
                try {
                    f = Frame.read(in);
                } catch (EOFException e) {
                    break;
                }
                if (f.type == Msg.BYE) {
                    break;
                }
                try {
                    handle(f);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    server.backend.errors.incrementAndGet();
                    error(f.requestId, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        } catch (IOException e) {
            server.log.debug("Cliente desconectado: " + e.getMessage());
        } finally {
            server.backend.connectedClients.decrementAndGet();
            try {
                socket.close();
            } catch (IOException ignored) {
                // nada
            }
        }
    }

    private void handle(Frame f) throws Exception {
        PayloadReader r = f.reader();
        switch (f.type) {
            case Msg.LOGIN: {
                String user = r.str().trim();
                if (user.isEmpty()) {
                    error(f.requestId, "usuario vacio");
                    return;
                }
                session = server.backend.login(user);
                List<String> classes = server.models.classNames();
                PayloadWriter w = new PayloadWriter().i32(session.idClientChat).i32(classes.size());
                for (String c : classes) {
                    w.str(c);
                }
                send(Msg.LOGIN_OK, f.requestId, w.toBytes());
                server.log.debug("Login de " + user + " (idClientChat " + session.idClientChat + ")");
                return;
            }
            case Msg.IDENTIFY: {
                if (session == null) {
                    error(f.requestId, "primero haga LOGIN");
                    return;
                }
                long t0 = System.nanoTime();
                int format = r.u8();
                int len = r.i32();
                byte[] data = r.bytes(len);
                byte[] pixels = format == Msg.FORMAT_RAW ? data : ImagePrep.fromEncoded(data);
                if (pixels.length != Params.IN_SIZE) {
                    error(f.requestId, "imagen invalida");
                    return;
                }
                InferenceService.Prediction p = server.inference.submit(pixels).get(120, TimeUnit.SECONDS);
                long ms = (System.nanoTime() - t0) / 1000000;
                String label = server.models.className(p.classIndex);
                send(Msg.RESULT, f.requestId, new PayloadWriter().i32(p.classIndex).str(label).f32(p.confidence)
                        .i32(p.nodeId).str(p.nodeLang).i32((int) ms).toBytes());
                server.backend.record(session, label, p.nodeId, p.nodeLang, ms);
                return;
            }
            case Msg.IDENTIFY_BATCH: {
                if (session == null) {
                    error(f.requestId, "primero haga LOGIN");
                    return;
                }
                identifyBatch(f.requestId, r);
                return;
            }
            case Msg.HISTORY_REQ: {
                if (session == null) {
                    error(f.requestId, "primero haga LOGIN");
                    return;
                }
                List<String> h = server.backend.history(session);
                PayloadWriter w = new PayloadWriter().i32(h.size());
                for (String s : h) {
                    w.str(s);
                }
                send(Msg.HISTORY, f.requestId, w.toBytes());
                return;
            }
            case Msg.STATUS_REQ:
                send(Msg.STATUS, f.requestId, new PayloadWriter().str(server.status()).toBytes());
                return;
            case Msg.TRAIN_REQ: {
                if (server.trainer == null) {
                    error(f.requestId, NO_TRAIN);
                    return;
                }
                DistributedTrainer.Config c = new DistributedTrainer.Config();
                c.images = r.i32();
                c.epochs = r.i32();
                c.batch = r.i32();
                c.lr = r.f32();
                c.langs = r.str();
                c.maxNodes = r.i32();
                c.publish = r.u8() == 1;
                c.label = r.str();
                final int rid = f.requestId;
                DistributedTrainer.Result res = server.trainer.run(c, line -> {
                    server.log.info(line);
                    try {
                        send(Msg.TRAIN_PROGRESS, rid, new PayloadWriter().str(line).toBytes());
                    } catch (IOException ignored) {
                        // si el admin se desconecta, el entrenamiento sigue igual
                    }
                });
                send(Msg.TRAIN_DONE, rid, new PayloadWriter().f64(res.seconds).f64(res.loss).f64(res.accuracy)
                        .i32(res.nodesUsed).str(res.detail).toBytes());
                return;
            }
            case Msg.CROSSCHECK_REQ:
                if (server.trainer == null) {
                    error(f.requestId, NO_TRAIN);
                    return;
                }
                send(Msg.CROSSCHECK, f.requestId, new PayloadWriter().str(server.trainer.crossCheck()).toBytes());
                return;
            default:
                error(f.requestId, "mensaje no soportado: " + Msg.name(f.type));
        }
    }

    /**
     * Muchas imágenes de un solo cliente: todas entran juntas a la cola de inferencia, así que
     * los nodos se las reparten igual que si vinieran de muchos clientes. Cada resultado se
     * devuelve apenas está listo (RESULT_ITEM), en el orden en que terminan, y al final BATCH_DONE.
     */
    private void identifyBatch(int requestId, PayloadReader r) throws Exception {
        long t0 = System.nanoTime();
        int n = r.i32();
        if (n < 1 || n > MAX_BATCH_IMAGES) {
            error(requestId, "cantidad de imagenes invalida: " + n + " (maximo " + MAX_BATCH_IMAGES + ")");
            return;
        }
        final LinkedBlockingQueue<Integer> finished = new LinkedBlockingQueue<Integer>();
        List<CompletableFuture<InferenceService.Prediction>> futures =
                new ArrayList<CompletableFuture<InferenceService.Prediction>>(n);
        for (int i = 0; i < n; i++) {
            byte[] pixels = r.bytes(Params.IN_SIZE);
            final int index = i;
            CompletableFuture<InferenceService.Prediction> fut = server.inference.submit(pixels);
            futures.add(fut);
            fut.whenComplete((p, ex) -> finished.add(index));
        }
        int ok = 0;
        for (int k = 0; k < n; k++) {
            Integer index = finished.poll(120, TimeUnit.SECONDS);
            if (index == null) {
                error(requestId, "tiempo de espera agotado: faltan " + (n - k) + " imagenes");
                return;
            }
            long ms = (System.nanoTime() - t0) / 1000000;
            PayloadWriter w = new PayloadWriter().i32(index);
            try {
                InferenceService.Prediction p = futures.get(index).get();
                String label = server.models.className(p.classIndex);
                w.u8(1).i32(p.classIndex).str(label).f32(p.confidence).i32(p.nodeId).str(p.nodeLang).i32((int) ms);
                server.backend.record(session, label, p.nodeId, p.nodeLang, ms);
                ok++;
            } catch (ExecutionException ex) {
                server.backend.errors.incrementAndGet();
                Throwable c = ex.getCause() == null ? ex : ex.getCause();
                w.u8(0).str(c.getMessage() == null ? c.toString() : c.getMessage());
            }
            send(Msg.RESULT_ITEM, requestId, w.toBytes());
        }
        long total = (System.nanoTime() - t0) / 1000000;
        send(Msg.BATCH_DONE, requestId, new PayloadWriter().i32(n).i32(ok).i32((int) total).toBytes());
        server.log.debug("Lote de " + n + " imagenes de " + session.user + " en " + total + " ms");
    }
}
