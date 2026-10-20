import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;

/**
 * Cliente de Open AI Cubes.
 *   java Cliente [ip]                                  chat de escritorio: elegir una foto e identificarla
 *   java Cliente carga <ip> <clientes> <peticiones>    prueba de carga: muchos clientes a la vez, con
 *                                                      un delay aleatorio de 0 a 200 ms entre consultas
 */
public class Cliente {
    static final Color AZUL = new Color(0x2F6FED), FONDO = new Color(0xF4F6FA), BORDE = new Color(0xDDE2EA), TEXTO = new Color(0x1F2633), GRIS = new Color(0x6B7385);
    static final Font F = new Font("Segoe UI", Font.PLAIN, 14), FB = new Font("Segoe UI", Font.BOLD, 14);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("carga")) carga(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        else chat(args.length > 0 ? args[0] : "127.0.0.1");
    }

    // ---------- Chat de escritorio ----------

    static File elegida;

    static void chat(String host) throws IOException {
        Socket s = new Socket(host, 5000);
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        JFrame ventana = new JFrame("Open AI Cubes - Cliente");
        // Encabezado: nombre y servidor al que está conectado
        JPanel cabecera = new JPanel(new BorderLayout());
        cabecera.setBackground(Color.WHITE);
        cabecera.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, BORDE), new EmptyBorder(12, 18, 12, 18)));
        JLabel titulo = etiqueta("Open AI Cubes", new Font("Segoe UI", Font.BOLD, 17), TEXTO);
        titulo.setIcon(cubo());
        titulo.setIconTextGap(10);
        cabecera.add(titulo, BorderLayout.WEST);
        cabecera.add(etiqueta("<html><span style='color:#2E9E5B'>&#9679;</span>  Conectado a " + host + ":5000</html>", F, GRIS), BorderLayout.EAST);
        // Conversación: burbujas del usuario a la derecha y del bot a la izquierda
        JPanel mensajes = new JPanel();
        mensajes.setLayout(new BoxLayout(mensajes, BoxLayout.Y_AXIS));
        mensajes.setBackground(FONDO);
        mensajes.setBorder(new EmptyBorder(14, 18, 14, 18));
        JPanel pegado = new JPanel(new BorderLayout());       // mantiene los mensajes arriba
        pegado.setBackground(FONDO);
        pegado.add(mensajes, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(pegado);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // Barra inferior: foto elegida, Elegir foto e Identificar
        JLabel seleccion = etiqueta("Ninguna foto elegida", F, GRIS);
        seleccion.setIconTextGap(10);
        JButton elegir = boton("Elegir foto…", false), identificar = boton("Identificar", true);
        identificar.setEnabled(false);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        botones.setOpaque(false);
        botones.add(etiqueta("Arrastra una foto aquí o", F, GRIS));
        botones.add(elegir);
        botones.add(identificar);
        JPanel abajo = new JPanel(new BorderLayout(10, 0));
        abajo.setBackground(Color.WHITE);
        abajo.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, BORDE), new EmptyBorder(10, 16, 10, 16)));
        abajo.add(seleccion, BorderLayout.WEST);
        abajo.add(botones, BorderLayout.EAST);

        Consumer<File> usar = f -> {
            try {
                seleccion.setIcon(miniatura(ImageIO.read(f), 40));
                seleccion.setText("<html><span style='color:#6B7385'>Foto elegida:</span>  <b>" + f.getName() + "</b></html>");
                elegida = f;
                identificar.setEnabled(true);
            } catch (Exception ex) {
                seleccion.setText("No se pudo leer " + f.getName());
            }
        };
        elegir.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(ventana) == JFileChooser.APPROVE_OPTION) usar.accept(fc.getSelectedFile());
        });
        identificar.addActionListener(e -> {
            File f = elegida;
            identificar.setEnabled(false);
            // La consulta va en otro hilo para que la ventana no se congele mientras responde el servidor.
            new Thread(() -> {
                try {
                    BufferedImage img = ImageIO.read(f);
                    long t0 = System.nanoTime();
                    String[] r;
                    synchronized (out) {
                        out.write(Cnn.aCifar(img));
                        out.flush();
                        r = in.readUTF().split(";");
                    }
                    long ms = (System.nanoTime() - t0) / 1000000;
                    SwingUtilities.invokeLater(() -> {
                        agregar(mensajes, scroll, burbujaUsuario(img, f.getName()), FlowLayout.RIGHT);
                        agregar(mensajes, scroll, burbujaBot(r[0], Double.parseDouble(r[1]), r[2], ms), FlowLayout.LEFT);
                        identificar.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        agregar(mensajes, scroll, etiqueta("Error: " + ex.getMessage(), F, new Color(0xD64545)), FlowLayout.LEFT);
                        identificar.setEnabled(true);
                    });
                }
            }).start();
        });
        // Arrastrar y soltar una foto en cualquier parte de la ventana
        ventana.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport t) {
                return t.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport t) {
                try {
                    List<File> archivos = (List<File>) t.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    usar.accept(archivos.get(0));
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
        ventana.add(cabecera, BorderLayout.NORTH);
        ventana.add(scroll, BorderLayout.CENTER);
        ventana.add(abajo, BorderLayout.SOUTH);
        ventana.setSize(820, 620);
        ventana.setLocationRelativeTo(null);
        ventana.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        ventana.setVisible(true);
    }

    static void agregar(JPanel mensajes, JScrollPane scroll, JComponent c, int lado) {
        JPanel fila = new JPanel(new FlowLayout(lado, 0, 0));
        fila.setOpaque(false);
        fila.add(c);
        mensajes.add(fila);
        mensajes.add(Box.createVerticalStrut(lado == FlowLayout.RIGHT ? 8 : 14));
        mensajes.revalidate();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));
    }

    static JComponent burbujaUsuario(BufferedImage img, String nombre) {
        JPanel b = caja(AZUL, new BorderLayout(10, 0));
        b.setBorder(new EmptyBorder(8, 8, 8, 14));
        b.add(new JLabel(miniatura(img, 64)), BorderLayout.WEST);
        b.add(etiqueta("<html><b>Identificar</b><br><span style='font-size:11px'>" + nombre + "</span></html>", F, Color.WHITE));
        return b;
    }

    static JComponent burbujaBot(String clase, double confianza, String nodo, long ms) {
        String nombre = clase.equals("avion") ? "avión" : clase.equals("automovil") ? "automóvil" : clase;
        JPanel b = caja(Color.WHITE, new BorderLayout(0, 2));
        b.setBorder(new EmptyBorder(10, 16, 10, 16));
        b.add(etiqueta(String.format(Locale.US, "Es un %s  ·  %.1f %%", nombre, confianza * 100), new Font("Segoe UI", Font.BOLD, 16), TEXTO), BorderLayout.NORTH);
        JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        meta.setOpaque(false);
        // El color de la etiqueta indica qué nodo respondió (Java naranja, Go celeste, Python verde)
        Color c = nodo.startsWith("java") ? new Color(0xE8833A) : nodo.startsWith("go") ? new Color(0x1FA8C9) : nodo.startsWith("python") ? new Color(0x4C9A2A) : GRIS;
        JPanel chip = caja(new Color(c.getRed(), c.getGreen(), c.getBlue(), 38), new FlowLayout(FlowLayout.CENTER, 8, 2));
        chip.add(etiqueta(nodo, new Font("Segoe UI", Font.BOLD, 12), c.darker()));
        meta.add(chip);
        meta.add(etiqueta(ms + " ms", new Font("Segoe UI", Font.PLAIN, 12), GRIS));
        b.add(meta, BorderLayout.SOUTH);
        return b;
    }

    /** Panel con esquinas redondeadas (burbujas y etiquetas). */
    static JPanel caja(Color fondo, LayoutManager lm) {
        JPanel p = new JPanel(lm) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fondo);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        return p;
    }

    static JLabel etiqueta(String s, Font f, Color c) {
        JLabel l = new JLabel(s);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }

    static JButton boton(String s, boolean primario) {
        JButton b = new JButton(s);
        b.setFont(FB);
        b.setFocusPainted(false);
        b.setBackground(primario ? AZUL : Color.WHITE);
        b.setForeground(primario ? Color.WHITE : TEXTO);
        b.setBorder(new CompoundBorder(new LineBorder(primario ? AZUL : BORDE, 1, true), new EmptyBorder(8, 16, 8, 16)));
        return b;
    }

    /** Recorte cuadrado central de la foto, reducido al tamaño indicado. */
    static ImageIcon miniatura(BufferedImage img, int lado) {
        BufferedImage b = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        int m = Math.min(img.getWidth(), img.getHeight()), x = (img.getWidth() - m) / 2, y = (img.getHeight() - m) / 2;
        g.drawImage(img, 0, 0, lado, lado, x, y, x + m, y + m, null);
        g.dispose();
        return new ImageIcon(b);
    }

    /** Logo: cubo isométrico pequeño. */
    static Icon cubo() {
        return new Icon() {
            public int getIconWidth() {
                return 22;
            }

            public int getIconHeight() {
                return 22;
            }

            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x + 11, y + 11);
                g2.setColor(new Color(0xFFD978));
                g2.fillPolygon(new int[]{0, 10, 0, -10}, new int[]{-11, -6, -1, -6}, 4);
                g2.setColor(new Color(0xD99A2B));
                g2.fillPolygon(new int[]{-10, 0, 0, -10}, new int[]{-6, -1, 11, 6}, 4);
                g2.setColor(new Color(0xB37718));
                g2.fillPolygon(new int[]{10, 0, 0, 10}, new int[]{-6, -1, 11, 6}, 4);
                g2.dispose();
            }
        };
    }

    // ---------- Prueba de carga ----------

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
