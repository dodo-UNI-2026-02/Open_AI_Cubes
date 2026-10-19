package cubes.server;

import cubes.net.Frame;
import cubes.net.Msg;
import cubes.net.PayloadReader;
import cubes.net.PayloadWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Acepta nodos en el puerto 6000, los registra y vigila que sigan vivos.
 * Un nodo que no envía nada en 10 s se considera caído.
 */
public final class NodeRegistry {

    public interface Listener {
        void nodeAdded(NodeHandle node);
    }

    private static final long TIMEOUT_MS = 10000;

    private final List<NodeHandle> nodes = new CopyOnWriteArrayList<NodeHandle>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Log log;

    public NodeRegistry(Log log) {
        this.log = log;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void start(final int port) throws IOException {
        final ServerSocket ss = new ServerSocket(port);
        log.info("Esperando nodos en el puerto " + port);
        Thread acceptor = new Thread(() -> {
            while (true) {
                try {
                    final Socket s = ss.accept();
                    new Thread(() -> handshake(s), "nodo-handshake").start();
                } catch (IOException e) {
                    log.info("Error aceptando nodo: " + e.getMessage());
                }
            }
        }, "acepta-nodos");
        acceptor.setDaemon(true);
        acceptor.start();

        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (NodeHandle n : nodes) {
                    if (now - n.lastSeen > TIMEOUT_MS) {
                        log.info("Nodo " + n.id + " sin heartbeat por " + (now - n.lastSeen) + " ms: se da por caido");
                        n.fail(new IOException("timeout de heartbeat"));
                        remove(n);
                    }
                }
            }
        }, "vigilante-nodos");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void handshake(Socket s) {
        try {
            s.setTcpNoDelay(true);
            DataInputStream in = NodeHandle.inputOf(s);
            DataOutputStream out = NodeHandle.outputOf(s);
            Frame f = Frame.read(in);
            if (f.type != Msg.REGISTER) {
                new Frame(Msg.ERROR, f.requestId, new PayloadWriter().str("se esperaba REGISTER").toBytes()).write(out);
                s.close();
                return;
            }
            PayloadReader r = f.reader();
            String lang = r.str();
            int cores = r.i32();
            int threads = r.i32();
            String host = r.str();
            NodeHandle node = new NodeHandle(nextId.getAndIncrement(), s, in, out, lang, cores, threads, host);
            node.send(Msg.REGISTER_OK, f.requestId, new PayloadWriter().i32(node.id).toBytes());
            nodes.add(node);
            log.info("Registrado " + node.describe());
            for (Listener l : listeners) {
                l.nodeAdded(node);
            }
            node.readLoop(this);
        } catch (IOException e) {
            log.info("Fallo en conexion de nodo: " + e.getMessage());
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
        }
    }

    void remove(NodeHandle n) {
        if (nodes.remove(n)) {
            log.info("Nodo " + n.id + " [" + n.lang + "] desconectado");
        }
    }

    public List<NodeHandle> alive() {
        List<NodeHandle> out = new ArrayList<NodeHandle>();
        for (NodeHandle n : nodes) {
            if (n.alive) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Selecciona nodos por lenguaje ("all" o lista "java,go") y como máximo maxNodes (0 = todos).
     * Se alternan los lenguajes y se prefiere una sola máquina por nodo, para que con M nodos
     * el trabajo quede de verdad en M máquinas distintas (cada máquina puede tener un nodo de
     * cada lenguaje esperando, y solo trabajan los seleccionados).
     */
    public List<NodeHandle> select(String langFilter, int maxNodes) {
        Set<String> langs = new HashSet<String>();
        boolean all = langFilter == null || langFilter.trim().isEmpty() || "all".equalsIgnoreCase(langFilter.trim());
        if (!all) {
            langs.addAll(Arrays.asList(langFilter.toLowerCase().replace(" ", "").split(",")));
        }
        List<String> order = new ArrayList<String>();
        List<List<NodeHandle>> byLang = new ArrayList<List<NodeHandle>>();
        for (NodeHandle n : alive()) {
            String l = n.lang.toLowerCase();
            if (!all && !langs.contains(l)) {
                continue;
            }
            int idx = order.indexOf(l);
            if (idx < 0) {
                order.add(l);
                byLang.add(new ArrayList<NodeHandle>());
                idx = order.size() - 1;
            }
            byLang.get(idx).add(n);
        }
        // Orden de candidatos alternando lenguajes: java1, python1, go1, java2, python2, ...
        List<NodeHandle> roundRobin = new ArrayList<NodeHandle>();
        for (int round = 0; ; round++) {
            boolean any = false;
            for (List<NodeHandle> group : byLang) {
                if (round < group.size()) {
                    roundRobin.add(group.get(round));
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        int limit = maxNodes <= 0 ? Integer.MAX_VALUE : maxNodes;
        List<NodeHandle> out = new ArrayList<NodeHandle>();
        Set<String> usedHosts = new HashSet<String>();
        // Primera pasada: una máquina por nodo. Segunda: si faltan, se permiten máquinas repetidas.
        for (int pass = 0; pass < 2; pass++) {
            for (NodeHandle n : roundRobin) {
                if (out.size() >= limit) {
                    break;
                }
                String hostKey = n.address + "/" + n.hostname;
                if (out.contains(n) || (pass == 0 && usedHosts.contains(hostKey))) {
                    continue;
                }
                out.add(n);
                usedHosts.add(hostKey);
            }
        }
        return out;
    }

    public void closeAll() {
        for (NodeHandle n : nodes) {
            n.close();
        }
    }
}
