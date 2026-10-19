package cubes.server;

import cubes.cnn.Dataset;
import cubes.cnn.Params;
import cubes.net.Msg;
import cubes.tools.Args;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Locale;

/**
 * Servidor central de Open AI Cubes.
 *
 *   - Puerto 5000: clientes (chat Swing, generador de carga, consola admin).
 *   - Puerto 6000: nodos de cómputo (Java, Python, Go).
 *
 * Uso: java -cp out cubes.server.CentralServer [--client-port 5000] [--node-port 6000]
 *      [--data data] [--model data/model.bin] [--results results] [--verbose]
 */
public final class CentralServer {

    final Log log = new Log();
    final Backend backend;
    final ModelStore models;
    final NodeRegistry registry;
    final InferenceService inference;
    final DistributedTrainer trainer;
    private final long startMillis = System.currentTimeMillis();

    CentralServer(Args args) throws IOException {
        log.setVerbose(args.has("verbose"));
        File dataDir = new File(args.get("data", "data"));
        File trainFile = new File(dataDir, "train.bin");
        File testFile = new File(dataDir, "test.bin");
        Dataset train = trainFile.exists() ? Dataset.load(trainFile) : null;
        Dataset test = testFile.exists() ? Dataset.load(testFile) : null;
        List<String> classes = Dataset.loadClassNames(new File(dataDir, "classes.txt"));

        File modelFile = new File(args.get("model", new File(dataDir, "model.bin").getPath()));
        Params model;
        if (modelFile.exists()) {
            model = Params.load(modelFile);
            log.info("Modelo cargado de " + modelFile + " (" + model.classes + " clases)");
        } else {
            model = Params.randomInit(classes.isEmpty() ? 4 : classes.size(), 42L);
            log.info("AVISO: no existe " + modelFile + "; se usan pesos aleatorios hasta entrenar con --publish");
        }
        while (classes.size() < model.classes) {
            classes.add("clase " + classes.size());
        }
        File results = new File(args.get("results", "results"));
        results.mkdirs();

        backend = new Backend(new File(results, "chat_history.txt"));
        models = new ModelStore(model, classes);
        registry = new NodeRegistry(log);
        inference = new InferenceService(models, registry, log);
        trainer = train == null ? null : new DistributedTrainer(registry, models, train, test,
                new File(dataDir, "init_weights.bin"), modelFile, new File(results, "distribuido.csv"), log);
        if (trainer == null) {
            log.info("AVISO: no hay " + trainFile + "; el entrenamiento distribuido queda deshabilitado");
        }
    }

    void start(int clientPort, int nodePort) throws IOException {
        registry.start(nodePort);
        final ServerSocket ss = new ServerSocket(clientPort, 512);
        log.info("Esperando clientes en el puerto " + clientPort);
        Thread acceptor = new Thread(() -> {
            while (true) {
                try {
                    Socket s = ss.accept();
                    Thread t = new Thread(new ClientHandler(s, this), "cliente-" + s.getPort());
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    log.info("Error aceptando cliente: " + e.getMessage());
                }
            }
        }, "acepta-clientes");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String status() {
        StringBuilder sb = new StringBuilder();
        long up = (System.currentTimeMillis() - startMillis) / 1000;
        long req = backend.requests.get();
        sb.append(String.format(Locale.ROOT, "Servidor activo hace %d s | clientes conectados %d | sesiones %d%n", up,
                backend.connectedClients.get(), backend.sessionCount()));
        sb.append(String.format(Locale.ROOT, "Consultas %d (errores %d, rechazadas %d) | latencia media %.1f ms | cola %d%n",
                req, backend.errors.get(), inference.rejected.get(),
                req == 0 ? 0.0 : (double) backend.latencyMsSum.get() / req, inference.queueSize()));
        sb.append(String.format(Locale.ROOT, "Inferencias en nodos %d | en servidor (sin nodos) %d | modelo v%d%n",
                inference.served.get() - inference.servedLocal.get(), inference.servedLocal.get(),
                models.snapshot().version));
        List<NodeHandle> nodes = registry.alive();
        sb.append("Nodos conectados: ").append(nodes.size()).append('\n');
        for (NodeHandle n : nodes) {
            sb.append("  ").append(n.describe()).append('\n');
        }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        Args args = new Args(a);
        CentralServer server = new CentralServer(args);
        server.start(args.getInt("client-port", Msg.CLIENT_PORT), args.getInt("node-port", Msg.NODE_PORT));
        server.log.info("Comandos: estado | verbose | salir");
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while (true) {
            line = console.readLine();
            if (line == null) {
                // Sin consola (por ejemplo en segundo plano): seguir atendiendo para siempre.
                Thread.sleep(Long.MAX_VALUE);
            }
            line = line.trim().toLowerCase();
            if (line.equals("estado") || line.equals("status")) {
                System.out.print(server.status());
            } else if (line.equals("verbose")) {
                server.log.setVerbose(true);
            } else if (line.equals("salir") || line.equals("exit")) {
                server.registry.closeAll();
                System.exit(0);
            }
        }
    }
}
