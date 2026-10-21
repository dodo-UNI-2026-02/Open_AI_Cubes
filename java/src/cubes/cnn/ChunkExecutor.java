package cubes.cnn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Ejecutor de micro chunks.
 *
 * Un micro chunk es un rango [from, to) de trabajo independiente (filtros de una
 * convolución, neuronas de una capa densa, canales de un pooling). forRange reparte
 * el rango completo entre H hilos y espera a que todos terminen: esa espera es la
 * barrera entre capas, porque la capa siguiente necesita el resultado completo.
 *
 * Con H = 1 todo corre en el hilo que llama (modo secuencial, sin pool).
 */
public final class ChunkExecutor implements AutoCloseable {

    public interface RangeTask {
        void run(int from, int to);
    }

    private final int threads;
    private final ExecutorService pool;

    public ChunkExecutor(int threads) {
        this.threads = Math.max(1, threads);
        if (this.threads == 1) {
            this.pool = null;
        } else {
            this.pool = Executors.newFixedThreadPool(this.threads, new ThreadFactory() {
                private int count = 0;

                @Override
                public synchronized Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "chunk-" + (count++));
                    t.setDaemon(true);
                    return t;
                }
            });
        }
    }

    /** Divide [0, n) en hasta H micro chunks contiguos y espera a todos (barrera). */
    public void forRange(int n, final RangeTask task) {
        if (n <= 0) {
            return;
        }
        if (pool == null || n == 1) {
            task.run(0, n);
            return;
        }
        int parts = Math.min(threads, n);
        List<Future<?>> futures = new ArrayList<Future<?>>(parts);
        for (int p = 0; p < parts; p++) {
            final int from = (int) ((long) n * p / parts);
            final int to = (int) ((long) n * (p + 1) / parts);
            futures.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    task.run(from, to);
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException) {
                    throw (RuntimeException) c;
                }
                throw new RuntimeException(c);
            }
        }
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
