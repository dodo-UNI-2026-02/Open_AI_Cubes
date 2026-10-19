package cubes.client;

import cubes.cnn.Dataset;
import cubes.cnn.Params;
import cubes.net.Msg;
import cubes.tools.Args;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generador de carga: simula muchos clientes de chat a la vez. Cada cliente se conecta,
 * hace LOGIN y envía R consultas "Identificar" con un delay aleatorio entre ellas.
 * Mide latencia (media y percentiles), throughput, errores y acierto, para encontrar
 * el límite del sistema.
 *
 * Ejemplos:
 *   java -cp out cubes.client.LoadGenerator --host 127.0.0.1 --clients 100 --requests 20 --min-delay 0 --max-delay 200
 *   java -cp out cubes.client.LoadGenerator --ramp 10,50,100,200,400 --requests 10 --csv results/carga.csv
 */
public final class LoadGenerator {

    static final class Level {
        int clients;
        long sent, ok, errors, correct;
        double seconds;
        long[] latencies;
        Map<String, Long> byLang;
    }

    public static void main(String[] a) throws Exception {
        Args args = new Args(a);
        String host = args.get("host", "127.0.0.1");
        int port = args.getInt("port", Msg.CLIENT_PORT);
        int requests = args.getInt("requests", 20);
        int minDelay = args.getInt("min-delay", 0);
        int maxDelay = args.getInt("max-delay", 200);
        Dataset test = Dataset.load(new File(args.get("test", "data/test.bin")));
        String label = args.get("label", "carga");
        List<Integer> levels = new ArrayList<Integer>();
        if (args.has("ramp")) {
            for (String s : args.get("ramp", "").split(",")) {
                levels.add(Integer.parseInt(s.trim()));
            }
        } else {
            levels.add(args.getInt("clients", 50));
        }
        System.out.printf(Locale.ROOT, "Generador de carga -> %s:%d | %d peticiones por cliente | delay %d-%d ms%n", host,
                port, requests, minDelay, maxDelay);
        for (int clients : levels) {
            Level lv = runLevel(host, port, clients, requests, minDelay, maxDelay, test);
            print(lv, requests);
            if (args.has("csv")) {
                appendCsv(new File(args.get("csv", "")), label, lv, requests, minDelay, maxDelay);
            }
        }
        System.exit(0);
    }

    static Level runLevel(final String host, final int port, final int clients, final int requests, final int minDelay,
                          final int maxDelay, final Dataset test) throws InterruptedException {
        final AtomicLong sent = new AtomicLong(), ok = new AtomicLong(), errors = new AtomicLong(), correct = new AtomicLong();
        final long[][] lat = new long[clients][];
        final AtomicInteger[] latCount = new AtomicInteger[clients];
        final Map<String, Long> byLang = new ConcurrentHashMap<String, Long>();
        final CountDownLatch ready = new CountDownLatch(clients);
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(clients);
        for (int c = 0; c < clients; c++) {
            final int id = c;
            lat[c] = new long[requests];
            latCount[c] = new AtomicInteger();
            Thread t = new Thread(() -> {
                Random rnd = new Random(1000 + id);
                ServerConnection conn = null;
                int serverErrors = 0;
                try {
                    ready.countDown();
                    go.await();
                    conn = new ServerConnection(host, port, 30000);
                    conn.login("carga-" + id);
                    byte[] px = new byte[Params.IN_SIZE];
                    for (int r = 0; r < requests; r++) {
                        int delay = minDelay + (maxDelay > minDelay ? rnd.nextInt(maxDelay - minDelay + 1) : 0);
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }
                        int idx = rnd.nextInt(test.size);
                        System.arraycopy(test.raw, test.pixelOffset(idx), px, 0, px.length);
                        sent.incrementAndGet();
                        long t0 = System.nanoTime();
                        try {
                            ServerConnection.Result res = conn.identify(Msg.FORMAT_RAW, px);
                            lat[id][latCount[id].getAndIncrement()] = (System.nanoTime() - t0) / 1000;
                            ok.incrementAndGet();
                            if (res.classIndex == test.label(idx)) {
                                correct.incrementAndGet();
                            }
                            byLang.merge(res.nodeLang, 1L, Long::sum);
                        } catch (ServerConnection.ServerError e) {
                            serverErrors++;
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // conexión rechazada o caída: las peticiones que faltaban cuentan como error
                    errors.addAndGet(requests - latCount[id].get() - serverErrors);
                } finally {
                    if (conn != null) {
                        conn.close();
                    }
                    done.countDown();
                }
            }, "cliente-carga-" + c);
            t.setDaemon(true);
            t.start();
        }
        ready.await();
        long t0 = System.nanoTime();
        go.countDown();
        done.await();
        Level lv = new Level();
        lv.seconds = (System.nanoTime() - t0) / 1e9;
        lv.clients = clients;
        lv.sent = sent.get();
        lv.ok = ok.get();
        lv.errors = errors.get();
        lv.correct = correct.get();
        int total = 0;
        for (AtomicInteger n : latCount) {
            total += n.get();
        }
        lv.latencies = new long[total];
        int k = 0;
        for (int c = 0; c < clients; c++) {
            for (int i = 0; i < latCount[c].get(); i++) {
                lv.latencies[k++] = lat[c][i];
            }
        }
        Arrays.sort(lv.latencies);
        lv.byLang = new TreeMap<String, Long>(byLang);
        return lv;
    }

    static double pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int i = (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1));
        return sorted[i] / 1000.0;
    }

    static double mean(long[] v) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (long x : v) {
            s += x;
        }
        return s / v.length / 1000.0;
    }

    static void print(Level lv, int requests) {
        System.out.printf(Locale.ROOT,
                "clientes=%d | enviadas=%d ok=%d errores=%d | %.2f s | %.1f resp/s | latencia media %.1f ms p50 %.1f p95 %.1f p99 %.1f max %.1f | acierto %.1f%% | por lenguaje %s%n",
                lv.clients, lv.clients * (long) requests, lv.ok, lv.errors, lv.seconds, lv.ok / lv.seconds,
                mean(lv.latencies), pct(lv.latencies, 0.50), pct(lv.latencies, 0.95), pct(lv.latencies, 0.99),
                pct(lv.latencies, 1.0), lv.ok == 0 ? 0.0 : 100.0 * lv.correct / lv.ok, lv.byLang);
    }

    static void appendCsv(File f, String label, Level lv, int requests, int minDelay, int maxDelay) throws IOException {
        boolean header = !f.exists();
        if (f.getAbsoluteFile().getParentFile() != null) {
            f.getAbsoluteFile().getParentFile().mkdirs();
        }
        PrintWriter w = new PrintWriter(new FileWriter(f, true));
        try {
            if (header) {
                w.println("etiqueta,clientes,peticiones_por_cliente,delay_min_ms,delay_max_ms,total,ok,errores,segundos,"
                        + "resp_por_s,lat_media_ms,p50_ms,p95_ms,p99_ms,max_ms,acierto");
            }
            w.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%.3f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f%n", label, lv.clients,
                    requests, minDelay, maxDelay, lv.clients * (long) requests, lv.ok, lv.errors, lv.seconds,
                    lv.ok / lv.seconds, mean(lv.latencies), pct(lv.latencies, 0.50), pct(lv.latencies, 0.95),
                    pct(lv.latencies, 0.99), pct(lv.latencies, 1.0), lv.ok == 0 ? 0.0 : (double) lv.correct / lv.ok);
        } finally {
            w.close();
        }
    }
}
