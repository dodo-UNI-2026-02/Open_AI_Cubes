import java.awt.BorderLayout;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Cliente de Open AI Cubes.
 *   java Cliente [ip]                                  chat de escritorio: elegir una foto e identificarla
 *   java Cliente carga <ip> <clientes> <peticiones>    prueba de carga: muchos clientes a la vez, con
 *                                                      un delay aleatorio de 0 a 200 ms entre consultas
 */
public class Cliente {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("carga")) carga(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        else chat(args.length > 0 ? args[0] : "127.0.0.1");
    }

    static void chat(String host) throws IOException {
        Socket s = new Socket(host, 5000);
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        JFrame ventana = new JFrame("Open AI Cubes - Cliente");
        JTextArea conversacion = new JTextArea("Conectado a " + host + ". Elige una foto para identificarla.\n");
        conversacion.setEditable(false);
        JButton boton = new JButton("Identificar imagen...");
        boton.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(ventana) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            try {
                out.write(Cnn.aCifar(ImageIO.read(f)));
                out.flush();
                String[] r = in.readUTF().split(";");
                conversacion.append(String.format("Yo: Identificar (%s)%nBot: %s (%.1f %%, respondió %s)%n", f.getName(), r[0], Double.parseDouble(r[1]) * 100, r[2]));
            } catch (Exception ex) {
                conversacion.append("Error: " + ex.getMessage() + "\n");
            }
        });
        ventana.add(new JScrollPane(conversacion), BorderLayout.CENTER);
        ventana.add(boton, BorderLayout.SOUTH);
        ventana.setSize(460, 520);
        ventana.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        ventana.setVisible(true);
    }

    /** Cada cliente es un hilo con su propio socket; se mide el throughput y la latencia. */
    static void carga(String host, int clientes, int peticiones) throws Exception {
        byte[] test = Cnn.cargarDataset("test.bin");
        String[] clases = Cnn.clasesNombres();
        int nTest = test.length / (Cnn.IN + 1);
        List<Long> latencias = Collections.synchronizedList(new ArrayList<Long>());
        AtomicInteger aciertos = new AtomicInteger(), errores = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(clientes);
        long t0 = System.nanoTime();
        for (int c = 0; c < clientes; c++) {
            pool.submit(() -> {
                Random r = new Random();
                try (Socket s = new Socket(host, 5000)) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    for (int i = 0; i < peticiones; i++) {
                        Thread.sleep(r.nextInt(201));
                        int k = r.nextInt(nTest), off = k * (Cnn.IN + 1) + 1;
                        long t = System.nanoTime();
                        out.write(test, off, Cnn.IN);
                        out.flush();
                        String clase = in.readUTF().split(";")[0];
                        latencias.add(System.nanoTime() - t);
                        if (clase.equals(clases[Cnn.etiqueta(test, k)])) aciertos.incrementAndGet();
                    }
                } catch (Exception e) {
                    errores.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
        double seg = (System.nanoTime() - t0) / 1e9;
        List<Long> l = new ArrayList<Long>(latencias);
        Collections.sort(l);
        int ok = l.size();
        double media = l.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50 = ok > 0 ? l.get(ok / 2) / 1e6 : 0, p95 = ok > 0 ? l.get(ok * 95 / 100) / 1e6 : 0;
        System.out.printf("%d clientes x %d peticiones: %d respuestas en %.2f s (%.1f resp/s), latencia media %.1f ms, p50 %.1f ms, p95 %.1f ms, %d clientes con error, acierto %.1f %%%n",
                clientes, peticiones, ok, seg, ok / seg, media, p50, p95, errores.get(), ok > 0 ? 100.0 * aciertos.get() / ok : 0);
        Cnn.csv("carga.csv", "clientes,peticiones_por_cliente,respuestas,segundos,resp_por_s,lat_media_ms,p50_ms,p95_ms,clientes_con_error,acierto",
                String.format(Locale.US, "%d,%d,%d,%.3f,%.2f,%.2f,%.2f,%.2f,%d,%.4f", clientes, peticiones, ok, seg, ok / seg, media, p50, p95, errores.get(), ok > 0 ? (double) aciertos.get() / ok : 0));
    }
}
