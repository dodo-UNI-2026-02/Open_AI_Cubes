import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor central de Open AI Cubes.
 *
 *  - Puerto 6000: se conectan los nodos de cómputo (Java, Python o Go).
 *  - Puerto 5000: se conectan los clientes; cada consulta entra a una cola compartida y el
 *    hilo de cada nodo saca lotes de hasta 16 imágenes (productor-consumidor). Un nodo
 *    rápido vuelve antes por más trabajo, así que el reparto se balancea solo.
 *  - Consola: "estado" y "entrenar ...": entrenamiento distribuido por paralelismo de datos;
 *    cada lote se reparte entre los nodos según su velocidad, cada nodo devuelve la suma de
 *    gradientes de su parte y el servidor los combina y actualiza los pesos.
 *  - Si un nodo se cae, sus consultas vuelven a la cola y su parte del lote pasa a otro nodo.
 *
 * Protocolo con los nodos (DataInput/DataOutput, big-endian):
 *   nodo -> servidor al conectarse:  UTF lenguaje, int hilos
 *   'W' int n, n floats                         nuevos pesos (sin respuesta)
 *   'I' int n, n*3072 bytes                     inferencia  -> int k, n*k floats (probabilidades)
 *   'T' int n, n bytes etiquetas, n*3072 bytes  entrenamiento -> P floats (gradientes), double pérdida, int aciertos
 * Protocolo con los clientes: el cliente envía 3072 bytes (foto 32x32), el servidor responde un UTF "clase;confianza;nodo".
 */
public class Servidor {
    static volatile float[] pesos;
    static volatile int version = 1;               // los nodos reciben 'W' solo cuando cambia
    static String[] clases;
    static final List<Nodo> nodos = new CopyOnWriteArrayList<Nodo>();
    static final LinkedBlockingDeque<Peticion> cola = new LinkedBlockingDeque<Peticion>();
    static final AtomicInteger ids = new AtomicInteger();
    static final Cnn local = new Cnn(Runtime.getRuntime().availableProcessors());   // respaldo y evaluación

    static class Peticion {
        final byte[] img;
        final CompletableFuture<String> respuesta = new CompletableFuture<String>();

        Peticion(byte[] img) {
            this.img = img;
        }
    }

    /** Un nodo conectado. Los métodos son synchronized: un solo pedido a la vez por socket. */
    static class Nodo {
        final int id = ids.incrementAndGet();
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final String lenguaje;
        final int hilos;
        int versionEnviada = 0;
        volatile double velocidad;                  // imágenes por segundo medidas al entrenar

        Nodo(Socket s) throws IOException {
            socket = s;
            in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            lenguaje = in.readUTF();
            hilos = in.readInt();
            velocidad = hilos;
        }

        String nombre() {
            return lenguaje + "#" + id;
        }

        private void enviarPesos(float[] w, int v) throws IOException {
            if (versionEnviada == v) return;
            out.writeByte('W');
            out.writeInt(w.length);
            for (float f : w) out.writeFloat(f);
            versionEnviada = v;
        }

        private void enviarImagenes(List<byte[]> imgs) throws IOException {
            out.writeInt(imgs.size());
            for (byte[] img : imgs) out.write(img);
        }

        synchronized float[][] inferir(List<byte[]> imgs) throws IOException {
            enviarPesos(pesos, version);
            out.writeByte('I');
            enviarImagenes(imgs);
            out.flush();
            int k = in.readInt();
            float[][] probs = new float[imgs.size()][k];
            for (float[] p : probs) for (int c = 0; c < k; c++) p[c] = in.readFloat();
            return probs;
        }

        synchronized double[] entrenar(float[] w, int v, byte[] etiquetas, List<byte[]> imgs, float[] g) throws IOException {
            long t0 = System.nanoTime();
            enviarPesos(w, v);
            out.writeByte('T');
            out.writeInt(imgs.size());
            out.write(etiquetas);
            for (byte[] img : imgs) out.write(img);
            out.flush();
            for (int i = 0; i < g.length; i++) g[i] = in.readFloat();
            double[] r = {in.readDouble(), in.readInt()};
            velocidad = imgs.size() / ((System.nanoTime() - t0) / 1e9);
            return r;
        }

        void cerrar() {
            nodos.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void main(String[] args) throws Exception {
        pesos = Cnn.cargarPesos(Cnn.DATA + "model.bin");
        clases = Cnn.clasesNombres();
        hilo(() -> aceptar(6000, true));
        hilo(() -> aceptar(5000, false));
        hilo(Servidor::respaldoLocal);
        System.out.println("Servidor listo: nodos en el puerto 6000, clientes en el 5000. Comandos: estado | entrenar <imagenes> <epocas> [java,go,python] [maxNodos] [guardar]");
        Scanner sc = new Scanner(System.in);
        while (sc.hasNextLine()) {
            String[] c = sc.nextLine().trim().split("\\s+");
            try {
                if (c[0].equals("estado")) {
                    for (Nodo n : nodos) System.out.printf("  %s  %d hilos  %.1f img/s%n", n.nombre(), n.hilos, n.velocidad);
                    System.out.println("  " + nodos.size() + " nodos, " + cola.size() + " consultas en cola");
                } else if (c[0].equals("entrenar")) {
                    entrenar(Integer.parseInt(c[1]), Integer.parseInt(c[2]), c.length > 3 ? c[3] : "all",
                            c.length > 4 ? Integer.parseInt(c[4]) : 0, Arrays.asList(c).contains("guardar"));
                }
            } catch (Exception e) {
                System.out.println("Error: " + e);
            }
        }
    }

    static void hilo(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    static void aceptar(int puerto, boolean deNodos) {
        try (ServerSocket ss = new ServerSocket(puerto, 1000)) {   // cola de 1000 conexiones pendientes (por defecto son 50)
            while (true) {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                hilo(() -> {
                    if (deNodos) atenderNodo(s);
                    else atenderCliente(s);
                });
            }
        } catch (IOException e) {
            System.out.println("No se pudo abrir el puerto " + puerto + ": " + e);
            System.exit(1);
        }
    }

    /** Consumidor: el hilo de cada nodo saca lotes de la cola y se los envía a su nodo. */
    static void atenderNodo(Socket s) {
        Nodo n;
        try {
            n = new Nodo(s);
        } catch (IOException e) {
            return;
        }
        nodos.add(n);
        System.out.println("Nodo conectado: " + n.nombre() + " (" + n.hilos + " hilos)");
        List<Peticion> lote = new ArrayList<Peticion>();
        try {
            while (true) {
                lote.add(cola.take());
                cola.drainTo(lote, 15);
                List<byte[]> imgs = new ArrayList<byte[]>();
                for (Peticion p : lote) imgs.add(p.img);
                float[][] probs = n.inferir(imgs);
                for (int i = 0; i < lote.size(); i++) lote.get(i).respuesta.complete(texto(probs[i], n.nombre()));
                lote.clear();
            }
        } catch (Exception e) {
            n.cerrar();
            for (int i = lote.size() - 1; i >= 0; i--) cola.offerFirst(lote.get(i));
            System.out.println("Nodo " + n.nombre() + " desconectado; " + lote.size() + " consultas vuelven a la cola");
        }
    }

    /** Si no hay ningún nodo conectado, el propio servidor responde para no dejar clientes esperando. */
    static void respaldoLocal() {
        while (true) {
            try {
                Peticion p = nodos.isEmpty() ? cola.poll(200, TimeUnit.MILLISECONDS) : null;
                if (p == null) {
                    if (!nodos.isEmpty()) Thread.sleep(200);
                    continue;
                }
                float[][] x = {Cnn.normalizar(p.img, 0)};
                p.respuesta.complete(texto(local.predecir(pesos, x)[0], "servidor"));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Productor: cada cliente tiene su hilo; sus consultas van a la cola compartida. */
    static void atenderCliente(Socket s) {
        try (Socket sock = s;
             DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {
            while (true) {
                byte[] img = new byte[Cnn.IN];
                in.readFully(img);
                Peticion p = new Peticion(img);
                cola.put(p);
                out.writeUTF(p.respuesta.get());
                out.flush();
            }
        } catch (Exception ignored) {
            // El cliente cerró la conexión.
        }
    }

    static String texto(float[] probs, String nodo) {
        int c = Cnn.argmax(probs);
        return String.format(Locale.US, "%s;%.4f;%s", clases[c], probs[c], nodo);
    }

    /** Entrenamiento distribuido desde pesos iniciales fijos (así los escenarios son comparables). */
    static void entrenar(int imagenes, int epocas, String lenguajes, int maxNodos, boolean guardar) throws Exception {
        byte[] train = Cnn.cargarDataset("train.bin");
        imagenes = Math.min(imagenes, train.length / (Cnn.IN + 1));
        final List<Nodo> usados = new ArrayList<Nodo>();
        for (Nodo n : nodos)
            if ((lenguajes.equals("all") || lenguajes.contains(n.lenguaje)) && (maxNodos <= 0 || usados.size() < maxNodos)) usados.add(n);
        if (usados.isEmpty()) throw new IllegalStateException("no hay nodos conectados de esos lenguajes");
        System.out.println("Entrenando con " + usados.size() + " nodos: " + nombres(usados));
        boolean unHilo = true;                      // para el CSV se anotan los nodos con que empezó
        Set<String> langs = new TreeSet<String>();
        for (Nodo n : usados) {
            unHilo &= n.hilos == 1;
            langs.add(n.lenguaje);
        }
        final int nodosIniciales = usados.size(), hilosNodo = usados.get(0).hilos;
        final float[] w = Cnn.pesosIniciales(clases.length, 7);
        float[] vel = new float[w.length];
        ExecutorService envios = Executors.newCachedThreadPool();
        int versionLote = -2;                       // versiones negativas: no chocan con las del chat (-1 = calibración)
        // Calibración (fuera del tiempo medido): 4 imágenes por nodo para conocer su velocidad.
        List<Future<Object[]>> calibracion = new ArrayList<Future<Object[]>>();
        for (Nodo n : usados) calibracion.add(envios.submit(() -> parte(n, usados, w, -1, train, 0, 4)));
        for (Future<Object[]> f : calibracion) f.get();
        long t0 = System.nanoTime();
        for (int e = 1; e <= epocas; e++) {
            double perdida = 0;
            int aciertos = 0;
            for (int ini = 0; ini < imagenes; ini += 64) {
                final int m = Math.min(64, imagenes - ini), v = versionLote--, inicio = ini;
                // Reparto proporcional a la velocidad medida de cada nodo.
                final List<Nodo> activos = new ArrayList<Nodo>(usados);
                double total = 0;
                for (Nodo n : activos) total += n.velocidad;
                int[] partes = new int[activos.size()];
                int asignadas = 0;
                for (int i = 0; i < partes.length; i++) asignadas += partes[i] = (int) (m * activos.get(i).velocidad / total);
                // El sobrante va a los más rápidos: darle 1 imagen a un nodo lento haría esperar a todo el lote.
                Integer[] orden = new Integer[partes.length];
                for (int i = 0; i < orden.length; i++) orden[i] = i;
                Arrays.sort(orden, (x, y) -> Double.compare(activos.get(y).velocidad, activos.get(x).velocidad));
                for (int i = 0; asignadas < m; i = (i + 1) % partes.length, asignadas++) partes[orden[i]]++;
                List<Future<Object[]>> futuros = new ArrayList<Future<Object[]>>();
                int desde = inicio;
                for (int i = 0; i < partes.length; i++) {
                    final int a = desde, cant = partes[i];
                    final Nodo preferido = activos.get(i);
                    desde += cant;
                    if (cant > 0) futuros.add(envios.submit(() -> parte(preferido, usados, w, v, train, a, cant)));
                }
                float[] suma = new float[w.length];
                for (Future<Object[]> f : futuros) {
                    Object[] r = f.get();
                    float[] g = (float[]) r[0];
                    for (int i = 0; i < g.length; i++) suma[i] += g[i];
                    perdida += ((double[]) r[1])[0];
                    aciertos += (int) ((double[]) r[1])[1];
                }
                Cnn.sgd(w, vel, suma, m, 0.01f, 0.9f);
                usados.retainAll(nodos);
            }
            System.out.printf("Época %d: pérdida %.4f, acierto en entrenamiento %.1f %%%n", e, perdida / imagenes, 100.0 * aciertos / imagenes);
        }
        envios.shutdown();
        double seg = (System.nanoTime() - t0) / 1e9;
        byte[] test = Cnn.cargarDataset("test.bin");
        double acc = local.acierto(w, test, test.length / (Cnn.IN + 1));   // las 4000 imágenes de prueba
        System.out.printf("%d imágenes x %d épocas en %.2f s (%.1f img/s), acierto en prueba %.1f %%%n", imagenes, epocas, seg, imagenes * epocas / seg, acc * 100);
        Cnn.csv("resultados.csv", Cnn.CABECERA, String.format(Locale.US, "%s,%s,%d,%d,%d,%d,%.3f,%.2f,%.4f",
                unHilo ? "distribuido" : "paralelo_distribuido", String.join("+", langs), nodosIniciales,
                hilosNodo, imagenes, epocas, seg, imagenes * epocas / seg, acc));
        if (guardar) {
            pesos = w;
            version++;
            Cnn.guardarPesos(w, Cnn.DATA + "model.bin");
            System.out.println("Modelo nuevo publicado para el chat y guardado en data/model.bin");
        }
    }

    /** Envía una parte del lote; si el nodo cae, la reintenta en otro nodo vivo (o la calcula el servidor). */
    static Object[] parte(Nodo preferido, List<Nodo> usados, float[] w, int v, byte[] train, int desde, int cant) throws IOException {
        byte[] etiquetas = new byte[cant];
        List<byte[]> imgs = new ArrayList<byte[]>();
        for (int i = 0; i < cant; i++) {
            etiquetas[i] = (byte) Cnn.etiqueta(train, desde + i);
            int off = (desde + i) * (Cnn.IN + 1) + 1;
            imgs.add(Arrays.copyOfRange(train, off, off + Cnn.IN));
        }
        Nodo n = preferido;
        while (n != null) {
            float[] g = new float[w.length];
            try {
                return new Object[]{g, n.entrenar(w, v, etiquetas, imgs, g)};
            } catch (IOException e) {
                System.out.println("Nodo " + n.nombre() + " falló; se reasignan " + cant + " imágenes");
                n.cerrar();
                n = null;
                for (Nodo otro : nodos) if (usados.contains(otro)) n = otro;
            }
        }
        float[][] x = new float[cant][];
        int[] y = new int[cant];
        for (int i = 0; i < cant; i++) {
            x[i] = Cnn.normalizar(imgs.get(i), 0);
            y[i] = etiquetas[i];
        }
        float[] g = new float[w.length];
        return new Object[]{g, local.entrenarLote(w, x, y, g)};
    }

    static String nombres(List<Nodo> l) {
        StringBuilder sb = new StringBuilder();
        for (Nodo n : l) sb.append(n.nombre()).append(' ');
        return sb.toString().trim();
    }
}
