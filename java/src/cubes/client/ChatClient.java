package cubes.client;

import cubes.cnn.Dataset;
import cubes.cnn.ImagePrep;
import cubes.cnn.Params;
import cubes.net.Msg;
import cubes.tools.Args;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Módulo Frontend: cliente de escritorio en Swing.
 *
 * El usuario inicia sesión y envía imágenes de varias formas: el botón "Identificar imagen",
 * arrastrando fotos o carpetas a la ventana, pegando una imagen con Ctrl+V, o con
 * "Identificar carpeta", que manda muchas imágenes en una sola petición (IDENTIFY_BATCH) para
 * que el servidor las reparta entre todos los nodos. El bot responde con la clase reconocida
 * ("Bot: gato"), la confianza y el nodo que la calculó.
 *
 * Uso: java -cp out cubes.client.ChatClient [--host 127.0.0.1] [--port 5000] [--user ana]
 *      [--test data/test.bin] [--alto 780] [--demo N] [--demo-batch N] [--demo-folder RUTA]
 */
public final class ChatClient {

    // Paleta
    private static final Color NAVY = new Color(0x1F3A5F);
    private static final Color BLUE = new Color(0x2F6FDE);
    private static final Color BG = new Color(0xF4F6FA);
    private static final Color MINE = new Color(0xDCE9FF);
    private static final Color BOT = Color.WHITE;
    private static final Color TEXT = new Color(0x1D1D1F);
    private static final Color MUTED = new Color(0x6B7280);
    private static final Color OK = new Color(0x2E7D32);
    private static final Color BAD = new Color(0xC62828);
    private static final Color[] NODE_COLORS = {
        // sin verde ni rojo, que se usan para acierto y error
        new Color(0x2F6FDE), new Color(0xE07B00), new Color(0x9B4DCA), new Color(0x1A9BAA),
        new Color(0x8C6D1F), new Color(0xD6458F), new Color(0x5C6BC0), new Color(0x607D8B),
    };

    private static final int MAX_FOLDER_IMAGES = 2000;
    private static final int SAMPLE_BATCH = 200;
    /** Prueba de carga desde el chat: delay aleatorio entre consultas, como pide el enunciado. */
    private static final int LOAD_MIN_DELAY_MS = 0, LOAD_MAX_DELAY_MS = 200;
    private static final Color LOAD_GREEN = new Color(0x0F6E56);
    private static final String[] IMAGE_EXT = {"jpg", "jpeg", "png", "bmp", "gif"};

    private final ServerConnection conn;
    private final ServerConnection.LoginInfo info;
    private final String user;
    private final Dataset samples;
    private final Random rnd = new Random();
    private final Map<Integer, Color> nodeColors = new HashMap<Integer, Color>();

    private JFrame frame;
    private MessagesPanel messages;
    private JScrollPane scroll;
    private final List<JButton> actions = new ArrayList<JButton>();
    private JButton sampleButton;
    private JButton sampleBatchButton;
    private JButton loadButton;
    private JSpinner loadCount;
    private JLabel status;
    private boolean busy;
    private static int windowHeight = 780;

    ChatClient(ServerConnection conn, ServerConnection.LoginInfo info, String user, Dataset samples) {
        this.conn = conn;
        this.info = info;
        this.user = user;
        this.samples = samples;
    }

    // ------------------------------------------------------------------ ventana

    private void buildUi() {
        frame = new JFrame("Cliente - Open AI Cubes");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(640, windowHeight);
        frame.setMinimumSize(new Dimension(520, 520));
        frame.setLocationByPlatform(true);

        messages = new MessagesPanel();
        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBackground(BG);
        messages.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
        scroll = new JScrollPane(messages);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().setBackground(BG);

        JButton bImage = new FlatButton("Identificar imagen", BLUE, Color.WHITE);
        JButton bFolder = new FlatButton("Identificar carpeta", BLUE, Color.WHITE);
        sampleButton = new FlatButton("Imagen de prueba", new Color(0xE8EEF8), NAVY);
        sampleBatchButton = new FlatButton("Lote de prueba (" + SAMPLE_BATCH + ")", new Color(0xE8EEF8), NAVY);
        bImage.addActionListener(e -> chooseImages());
        bFolder.addActionListener(e -> chooseFolder());
        sampleButton.addActionListener(e -> identifySample());
        sampleBatchButton.addActionListener(e -> identifySampleBatch(SAMPLE_BATCH));
        actions.addAll(Arrays.asList(bImage, bFolder, sampleButton, sampleBatchButton));
        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);
        for (JButton b : actions) {
            grid.add(b);
        }
        // tercera fila: prueba de carga con N consultas de una imagen, cada una con delay aleatorio
        loadCount = new JSpinner(new SpinnerNumberModel(100, 1, 5000, 10));
        loadCount.setPreferredSize(new Dimension(78, 30));
        loadButton = new FlatButton("Prueba de carga (delay " + LOAD_MIN_DELAY_MS + "–" + LOAD_MAX_DELAY_MS + " ms)",
                LOAD_GREEN, Color.WHITE);
        loadButton.addActionListener(e -> runLoadTest(((Number) loadCount.getValue()).intValue()));
        actions.add(loadButton);
        JPanel countBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        countBox.setOpaque(false);
        countBox.add(text("Consultas", 12f, Font.PLAIN, MUTED));
        countBox.add(loadCount);
        JPanel loadRow = new JPanel(new BorderLayout(8, 0));
        loadRow.setOpaque(false);
        loadRow.add(countBox, BorderLayout.WEST);
        loadRow.add(loadButton, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new BorderLayout(0, 8));
        buttons.setOpaque(false);
        buttons.add(grid, BorderLayout.CENTER);
        buttons.add(loadRow, BorderLayout.SOUTH);

        status = new JLabel(" ");
        status.setForeground(MUTED);
        status.setFont(status.getFont().deriveFont(12f));
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xE3E7EE)),
                BorderFactory.createEmptyBorder(10, 12, 8, 12)));
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);

        frame.getContentPane().add(buildHeader(), BorderLayout.NORTH);
        frame.getContentPane().add(scroll, BorderLayout.CENTER);
        frame.getContentPane().add(bottom, BorderLayout.SOUTH);

        // Arrastrar fotos o carpetas a cualquier parte de la ventana, y Ctrl+V para pegar una imagen
        TransferHandler dnd = new DropHandler();
        frame.setTransferHandler(dnd);
        messages.setTransferHandler(dnd);
        scroll.setTransferHandler(dnd);
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()), "pegar");
        frame.getRootPane().getActionMap().put("pegar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pasteFromClipboard();
            }
        });

        refreshButtons();
        setStatus("Conectado al servidor. Puedo reconocer: " + classList() + ".");
        Bubble hello = botBubble();
        hello.add(text("Hola " + user + ". Puedo reconocer " + classList() + ".", 14f, Font.PLAIN, TEXT));
        hello.add(gap(4));
        hello.add(text("Arrastra aquí una foto o una carpeta, pega una imagen con Ctrl+V o usa los botones de abajo.",
                13f, Font.PLAIN, MUTED));
        addRow(hello, false);
        frame.setVisible(true);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, NAVY, getWidth(), getHeight(), BLUE));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(text("Chat de reconocimiento de imágenes", 18f, Font.BOLD, Color.WHITE));
        left.add(gap(4));
        left.add(text("Usuario Validado: " + user, 13f, Font.PLAIN, new Color(0xD6E4FF)));
        left.add(text("idClientChat " + String.format("%04d", info.idClientChat), 13f, Font.PLAIN, new Color(0xD6E4FF)));
        header.add(left, BorderLayout.CENTER);
        JButton hist = new FlatButton("Historial", new Color(255, 255, 255, 40), Color.WHITE);
        hist.addActionListener(e -> showHistory());
        actions.add(hist);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(hist);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    // ------------------------------------------------------------------ utilidades de UI

    private String classList() {
        List<String> names = new ArrayList<String>();
        for (String c : info.classes) {
            names.add(pretty(c));
        }
        return String.join(", ", names);
    }

    /** Nombres de clase para mostrar (classes.txt los guarda sin tildes). */
    static String pretty(String c) {
        if (c == null) {
            return "";
        }
        switch (c) {
            case "avion": return "avión";
            case "automovil": return "automóvil";
            case "camion": return "camión";
            case "pajaro": return "pájaro";
            default: return c;
        }
    }

    private static JLabel text(String s, float size, int style, Color color) {
        JLabel l = new JLabel(html(s));
        l.setFont(l.getFont().deriveFont(style, size));
        l.setForeground(color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Texto para JLabel: las líneas largas se ajustan a un ancho fijo dentro del globo. */
    private static String html(String s) {
        String body = escape(s);
        return s.length() > 48 ? "<html><div style='width:360px'>" + body + "</div></html>" : "<html>" + body + "</html>";
    }

    private static Component gap(int h) {
        JComponent c = (JComponent) Box.createVerticalStrut(h);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Bubble myBubble() {
        return new Bubble(MINE);
    }

    private Bubble botBubble() {
        return new Bubble(BOT);
    }

    private void addRow(Bubble b, boolean mine) {
        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 5)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(b);
        messages.add(row);
        messages.revalidate();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            messages.revalidate();
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scroll.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        });
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void setBusy(boolean b) {
        busy = b;
        refreshButtons();
        frame.setCursor(b ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void refreshButtons() {
        for (JButton bt : actions) {
            bt.setEnabled(!busy);
        }
        sampleButton.setEnabled(!busy && samples != null);
        sampleBatchButton.setEnabled(!busy && samples != null);
        if (loadButton != null) {
            loadButton.setEnabled(!busy && samples != null);
            loadCount.setEnabled(!busy);
        }
    }

    private Color nodeColor(int nodeId) {
        Color c = nodeColors.get(nodeId);
        if (c == null) {
            c = nodeId == 0 ? Color.GRAY : NODE_COLORS[nodeColors.size() % NODE_COLORS.length];
            nodeColors.put(nodeId, c);
        }
        return c;
    }

    private static String nodeName(int nodeId, String lang) {
        return nodeId == 0 ? "servidor (sin nodos)" : "nodo " + nodeId + " · " + lang;
    }

    private static BufferedImage thumbnail(BufferedImage img, int side) {
        if (img == null) {
            return null;
        }
        int w = img.getWidth(), h = img.getHeight();
        double k = (double) side / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * k)), th = Math.max(1, (int) Math.round(h * k));
        BufferedImage small = w > tw * 2 ? ImagePrep.shrink(img, Math.max(tw, th)) : img;
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(small, 0, 0, tw, th, Color.WHITE, null);
        g.dispose();
        return out;
    }

    /** Las imágenes de prueba son de 32x32: se agrandan sin suavizar para que se vean nítidas. */
    private static BufferedImage enlarge(BufferedImage img, int side) {
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, 0, 0, side, side, null);
        g.dispose();
        return out;
    }

    private static JLabel picture(BufferedImage img) {
        JLabel l = new JLabel(new ImageIcon(img));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        return l;
    }

    // ------------------------------------------------------------------ prueba de carga

    /** Resultado de una prueba de carga hecha desde este chat. */
    private static final class LoadStats {
        int sent, ok, errors, hits;
        long totalMs;
        final List<Long> latencies = new ArrayList<Long>();
        final Map<String, Integer> byNode = new LinkedHashMap<String, Integer>();
    }

    /**
     * Manda n consultas de una imagen de prueba cada una, una tras otra por la misma conexión, con un delay
     * aleatorio de 0 a 200 ms entre consultas. Si varios integrantes la lanzan a la vez, cada uno ve cómo sube
     * su latencia porque el servidor y los nodos atienden a todos. Al final muestra latencia, throughput y
     * reparto por nodo, y agrega una fila a results/carga_clientes_reales.csv.
     */
    private void runLoadTest(final int n) {
        Bubble mine = myBubble();
        mine.add(text("Yo: Prueba de carga", 14f, Font.BOLD, NAVY));
        mine.add(text(n + " consultas · 1 imagen c/u · delay " + LOAD_MIN_DELAY_MS + "–" + LOAD_MAX_DELAY_MS + " ms",
                12f, Font.PLAIN, MUTED));
        addRow(mine, true);
        final Bubble bot = botBubble();
        final JLabel progress = text("Bot: enviando consultas...", 14f, Font.ITALIC, MUTED);
        bot.add(progress);
        addRow(bot, false);
        setBusy(true);
        setStatus("Prueba de carga: 0 de " + n);
        new SwingWorker<LoadStats, Integer>() {
            @Override
            protected LoadStats doInBackground() throws Exception {
                LoadStats st = new LoadStats();
                Random r = new Random();
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        Thread.sleep(LOAD_MIN_DELAY_MS + r.nextInt(LOAD_MAX_DELAY_MS - LOAD_MIN_DELAY_MS + 1));
                    }
                    int idx = r.nextInt(samples.size);
                    byte[] px = new byte[Params.IN_SIZE];
                    System.arraycopy(samples.raw, samples.pixelOffset(idx), px, 0, px.length);
                    long q0 = System.nanoTime();
                    st.sent++;
                    try {
                        ServerConnection.Result res = conn.identify(Msg.FORMAT_RAW, px);
                        st.latencies.add((System.nanoTime() - q0) / 1000000);
                        st.ok++;
                        String node = nodeName(res.nodeId, res.nodeLang);
                        Integer c = st.byNode.get(node);
                        st.byNode.put(node, c == null ? 1 : c + 1);
                        String truth = sampleTruth(idx);
                        if (truth != null && truth.equals(res.label)) {
                            st.hits++;
                        }
                    } catch (Exception ex) {
                        st.errors++;
                    }
                    publish(i + 1);
                }
                st.totalMs = (System.nanoTime() - t0) / 1000000;
                return st;
            }

            @Override
            protected void process(List<Integer> chunks) {
                int done = chunks.get(chunks.size() - 1);
                progress.setText("Bot: enviando consultas... " + done + " de " + n);
                setStatus("Prueba de carga: " + done + " de " + n);
            }

            @Override
            protected void done() {
                setBusy(false);
                bot.removeAll();
                try {
                    LoadStats st = get();
                    List<Long> lat = new ArrayList<Long>(st.latencies);
                    Collections.sort(lat);
                    double mean = 0;
                    for (long v : lat) {
                        mean += v;
                    }
                    mean = lat.isEmpty() ? 0 : mean / lat.size();
                    long p50 = percentile(lat, 50), p95 = percentile(lat, 95), max = lat.isEmpty() ? 0 : lat.get(lat.size() - 1);
                    double secs = st.totalMs / 1000.0;
                    double rps = secs > 0 ? st.ok / secs : 0;
                    JLabel title = text("x", 15f, Font.BOLD, TEXT);
                    title.setText("<html><font color='#2E7D32'>Bot:</font>&nbsp;prueba de carga terminada</html>");
                    bot.add(title);
                    bot.add(gap(4));
                    bot.add(text(String.format(Locale.ROOT, "Latencia media %.0f ms · p50 %d · p95 %d · máx %d ms",
                            mean, p50, p95, max), 13f, Font.BOLD, NAVY));
                    bot.add(text(String.format(Locale.ROOT, "%d de %d respondidas · %d errores · %.1f s · %.1f resp/s",
                            st.ok, st.sent, st.errors, secs, rps), 12f, Font.PLAIN, MUTED));
                    StringBuilder nodes = new StringBuilder();
                    for (Map.Entry<String, Integer> e : st.byNode.entrySet()) {
                        nodes.append(nodes.length() == 0 ? "" : " · ").append(e.getKey()).append(": ").append(e.getValue());
                    }
                    bot.add(text(nodes.length() == 0 ? "ningún nodo respondió" : nodes.toString(), 12f, Font.PLAIN, MUTED));
                    if (st.ok > 0) {
                        bot.add(text(String.format(Locale.ROOT, "Acierto %.0f %% (%d de %d)", 100.0 * st.hits / st.ok,
                                st.hits, st.ok), 12f, Font.PLAIN, MUTED));
                    }
                    setStatus(String.format(Locale.ROOT, "Prueba de carga: %d de %d · %.1f resp/s · latencia media %.0f ms",
                            st.ok, st.sent, rps, mean));
                    saveLoadRow(n, st, secs, rps, mean, p50, p95, max, nodes.toString());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    bot.add(text("Bot: la prueba de carga falló: " + c.getMessage(), 14f, Font.PLAIN, BAD));
                    setStatus("La prueba de carga no terminó.");
                }
                bot.revalidate();
                scrollToBottom();
            }
        }.execute();
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** Una fila por prueba en results/carga_clientes_reales.csv (para la tabla de clientes reales del informe). */
    private void saveLoadRow(int n, LoadStats st, double secs, double rps, double mean, long p50, long p95, long max,
                             String nodes) {
        File f = new File("results", "carga_clientes_reales.csv");
        try {
            f.getAbsoluteFile().getParentFile().mkdirs();
            boolean header = !f.exists();
            String host;
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "?";
            }
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f, true), java.nio.charset.StandardCharsets.UTF_8));
            try {
                if (header) {
                    w.println("fecha,usuario,maquina,consultas,ok,errores,segundos,resp_por_s,lat_media_ms,p50_ms,p95_ms,max_ms,acierto,por_nodo");
                }
                w.printf(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%.3f,%.2f,%.1f,%d,%d,%d,%.4f,\"%s\"%n",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()),
                        user.replace(",", " "), host, n, st.ok, st.errors, secs, rps, mean, p50, p95, max,
                        st.ok == 0 ? 0.0 : (double) st.hits / st.ok, nodes.replace("\"", "'"));
            } finally {
                w.close();
            }
        } catch (Exception e) {
            setStatus("No se pudo guardar " + f.getPath() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ una imagen

    /** Una imagen: se manda el archivo tal cual y el servidor la reduce a 32x32 (módulo de imágenes). */
    private void identifyFile(File f) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                throw new IllegalArgumentException("no es una imagen JPG, PNG, BMP o GIF");
            }
            byte[] data = Files.readAllBytes(f.toPath());
            identifyOne(Msg.FORMAT_ENCODED, data, thumbnail(img, 160), f.getName(), truthFromPath(f));
        } catch (Exception ex) {
            setStatus("No se pudo leer " + f.getName() + ": " + ex.getMessage());
        }
    }

    private void identifySample() {
        int idx = rnd.nextInt(samples.size);
        byte[] px = new byte[Params.IN_SIZE];
        System.arraycopy(samples.raw, samples.pixelOffset(idx), px, 0, px.length);
        identifyOne(Msg.FORMAT_RAW, px, enlarge(ImagePrep.toImage(px, 0), 128), "imagen de prueba #" + idx,
                sampleTruth(idx));
    }

    private void identifyOne(final int format, final byte[] data, BufferedImage preview, String name, final String truth) {
        Bubble mine = myBubble();
        mine.add(text("Yo: Identificar", 14f, Font.BOLD, NAVY));
        if (name != null) {
            mine.add(text(name, 12f, Font.PLAIN, MUTED));
        }
        if (preview != null) {
            mine.add(picture(preview));
        }
        addRow(mine, true);
        final Bubble bot = botBubble();
        final JLabel waiting = text("Bot: pensando...", 14f, Font.ITALIC, MUTED);
        bot.add(waiting);
        addRow(bot, false);
        setBusy(true);
        setStatus("Esperando respuesta del servidor...");
        final long t0 = System.nanoTime();
        new SwingWorker<ServerConnection.Result, Void>() {
            @Override
            protected ServerConnection.Result doInBackground() throws Exception {
                return conn.identify(format, data);
            }

            @Override
            protected void done() {
                setBusy(false);
                bot.removeAll();
                try {
                    ServerConnection.Result r = get();
                    long ms = (System.nanoTime() - t0) / 1000000;
                    JLabel line = text("x", 17f, Font.BOLD, TEXT);
                    line.setText("<html><font color='#2E7D32'>Bot:</font>&nbsp;" + escape(pretty(r.label)) + "</html>");
                    bot.add(line);
                    bot.add(new ConfidenceBar(r.confidence, nodeColor(r.nodeId)));
                    String detail = String.format(Locale.ROOT, "%.0f %% de confianza · %s · %d ms", 100 * r.confidence,
                            nodeName(r.nodeId, r.nodeLang), ms);
                    bot.add(text(detail, 12f, Font.PLAIN, MUTED));
                    if (truth != null) {
                        boolean hit = truth.equals(r.label);
                        bot.add(text((hit ? "Correcto" : "Incorrecto") + ": era " + pretty(truth), 12f, Font.BOLD,
                                hit ? OK : BAD));
                    }
                    setStatus("Última respuesta en " + ms + " ms, calculada por el " + nodeName(r.nodeId, r.nodeLang) + ".");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    bot.add(text("Bot: error: " + c.getMessage(), 14f, Font.PLAIN, BAD));
                    setStatus("Error de comunicación con el servidor.");
                }
                bot.revalidate();
                bot.repaint();
                scrollToBottom();
            }
        }.execute();
    }

    // ------------------------------------------------------------------ muchas imágenes

    /** Una imagen lista para enviar en un lote. */
    private static final class Item {
        String name;
        byte[] pixels;
        BufferedImage thumb;
        String truth;
        ServerConnection.Result result;
        String error;
    }

    private static final class Arrived {
        final int index;
        final ServerConnection.Result result;
        final String error;

        Arrived(int index, ServerConnection.Result result, String error) {
            this.index = index;
            this.result = result;
            this.error = error;
        }
    }

    private void chooseImages() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Elige una o varias imágenes");
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("Imágenes (jpg, png, bmp, gif)", IMAGE_EXT));
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            handleFiles(Arrays.asList(fc.getSelectedFiles()), null);
        }
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Elige una carpeta con imágenes");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File dir = fc.getSelectedFile();
            handleFiles(Collections.singletonList(dir), dir.getName());
        }
    }

    /** Archivos o carpetas (elegidos o arrastrados): una sola imagen va sola; varias, en lote. */
    private void handleFiles(List<File> picked, String title) {
        if (busy) {
            setStatus("Espera a que termine la consulta anterior.");
            return;
        }
        List<File> images = new ArrayList<File>();
        for (File f : picked) {
            collectImages(f, images, 0);
        }
        if (images.isEmpty()) {
            setStatus("No encontré imágenes JPG, PNG, BMP o GIF ahí.");
            return;
        }
        Collections.sort(images);
        if (images.size() == 1 && picked.size() == 1 && picked.get(0).isFile()) {
            identifyFile(images.get(0));
            return;
        }
        int found = images.size();
        if (found > MAX_FOLDER_IMAGES) {
            // se toman imágenes repartidas por toda la carpeta, no solo las primeras
            List<File> picked2 = new ArrayList<File>(MAX_FOLDER_IMAGES);
            for (int i = 0; i < MAX_FOLDER_IMAGES; i++) {
                picked2.add(images.get((int) ((long) i * found / MAX_FOLDER_IMAGES)));
            }
            images = picked2;
        }
        if (title == null) {
            title = picked.size() == 1 ? picked.get(0).getName() : found + " archivos";
        }
        // se mezclan para que las miniaturas del chat muestren de todo (cada una guarda su etiqueta real)
        Collections.shuffle(images, new Random(7));
        final List<File> files = images;
        String label = "Yo: Identificar carpeta \"" + title + "\" (" + files.size() + " imágenes)";
        if (found > files.size()) {
            label += ", se toman " + files.size() + " de " + found;
        }
        runBatch(label, progress -> loadFiles(files, progress));
    }

    private void collectImages(File f, List<File> out, int depth) {
        if (out.size() > MAX_FOLDER_IMAGES * 2) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null || depth > 2) {
                return;
            }
            for (File c : children) {
                collectImages(c, out, depth + 1);
            }
        } else if (isImage(f)) {
            out.add(f);
        }
    }

    private static boolean isImage(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        for (String e : IMAGE_EXT) {
            if (n.endsWith("." + e)) {
                return true;
            }
        }
        return false;
    }

    /** Avance de la lectura de imágenes: cuántas listas de cuántas. */
    interface Progress {
        void step(int done, int total);
    }

    /** De dónde salen las imágenes de un lote (archivos o el conjunto de prueba). */
    interface BatchSource {
        List<Item> load(Progress progress) throws Exception;
    }

    /** Lee y reduce las imágenes en paralelo con todos los núcleos del cliente. */
    private List<Item> loadFiles(final List<File> files, final Progress progress) throws Exception {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicInteger ready = new AtomicInteger();
        try {
            List<Future<Item>> futures = new ArrayList<Future<Item>>();
            for (final File f : files) {
                futures.add(pool.submit(() -> {
                    Item it = new Item();
                    it.name = f.getName();
                    it.truth = truthFromPath(f);
                    try {
                        BufferedImage img = ImageIO.read(f);
                        if (img != null) {
                            it.pixels = ImagePrep.toCifar(img);
                            it.thumb = thumbnail(img, 56);
                        }
                    } catch (Exception ignored) {
                        // se informa abajo como imagen ilegible
                    }
                    progress.step(ready.incrementAndGet(), files.size());
                    return it;
                }));
            }
            List<Item> items = new ArrayList<Item>();
            for (Future<Item> fu : futures) {
                Item it = fu.get();
                if (it.pixels != null) {
                    items.add(it);
                }
            }
            return items;
        } finally {
            pool.shutdown();
        }
    }

    private void identifySampleBatch(final int n) {
        List<Item> items = new ArrayList<Item>();
        for (int k = 0; k < n; k++) {
            int idx = rnd.nextInt(samples.size);
            Item it = new Item();
            it.name = "#" + idx;
            it.pixels = new byte[Params.IN_SIZE];
            System.arraycopy(samples.raw, samples.pixelOffset(idx), it.pixels, 0, it.pixels.length);
            it.thumb = enlarge(ImagePrep.toImage(it.pixels, 0), 56);
            it.truth = sampleTruth(idx);
            items.add(it);
        }
        final List<Item> ready = items;
        runBatch("Yo: Identificar " + n + " imágenes de prueba", progress -> ready);
    }

    /**
     * Lote: primero se preparan las imágenes (loader), luego se mandan todas en un IDENTIFY_BATCH
     * y la burbuja del bot se actualiza a medida que llegan los resultados de cada nodo.
     */
    private void runBatch(String label, final BatchSource source) {
        Bubble mine = myBubble();
        mine.add(text(label, 14f, Font.BOLD, NAVY));
        addRow(mine, true);
        final BatchView view = new BatchView();
        addRow(view.bubble, false);
        setBusy(true);
        setStatus("Preparando las imágenes...");
        new SwingWorker<ServerConnection.BatchSummary, Object>() {
            List<Item> items;
            long t0;

            @Override
            protected ServerConnection.BatchSummary doInBackground() throws Exception {
                items = source.load((done, total) -> {
                    if (done % 20 == 0 || done == total) {
                        publish("Preparando imágenes: " + done + " de " + total);
                    }
                });
                if (items.isEmpty()) {
                    throw new IllegalStateException("ninguna imagen se pudo leer");
                }
                publish(items);
                List<byte[]> px = new ArrayList<byte[]>(items.size());
                for (Item it : items) {
                    px.add(it.pixels);
                }
                t0 = System.nanoTime();
                return conn.identifyBatch(px, (index, result, error) -> publish(new Arrived(index, result, error)));
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void process(List<Object> chunks) {
                for (Object o : chunks) {
                    if (o instanceof String) {
                        view.title.setText(html("Bot: " + o));
                        setStatus((String) o);
                    } else if (o instanceof List) {
                        view.start((List<Item>) o);
                        setStatus("Enviando " + ((List<Item>) o).size() + " imágenes al servidor en una sola petición...");
                    } else {
                        Arrived a = (Arrived) o;
                        view.arrived(a);
                    }
                }
                view.refresh();
                scrollToBottom();
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    ServerConnection.BatchSummary s = get();
                    long ms = (System.nanoTime() - t0) / 1000000;
                    view.finish(s, ms);
                    setStatus(String.format(Locale.ROOT, "%d imágenes en %.2f s, repartidas entre %d nodo(s).", s.images,
                            ms / 1000.0, view.nodes.size()));
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    view.fail(c.getMessage() == null ? c.toString() : c.getMessage());
                    setStatus("No se pudo completar el lote.");
                }
                scrollToBottom();
            }
        }.execute();
    }

    /** Burbuja del bot para un lote: barras por nodo, conteo por clase y miniaturas. */
    private final class BatchView {
        static final int GRID = 24;
        final Bubble bubble = botBubble();
        final JLabel title = text("Bot: preparando imágenes...", 15f, Font.BOLD, TEXT);
        final JLabel progress = text(" ", 12f, Font.PLAIN, MUTED);
        final NodeBars bars = new NodeBars();
        final JLabel classes = text(" ", 13f, Font.PLAIN, TEXT);
        JLabel more;
        final JPanel grid = new JPanel(new GridLayout(0, 6, 6, 6));
        final List<Cell> cells = new ArrayList<Cell>();
        final Map<Integer, String> nodes = new LinkedHashMap<Integer, String>();
        final Map<String, Integer> perClass = new LinkedHashMap<String, Integer>();
        List<Item> items;
        int arrived, hits, withTruth, errors;
        boolean finished;

        BatchView() {
            grid.setOpaque(false);
            grid.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(title);
            bubble.add(progress);
        }

        void start(List<Item> list) {
            items = list;
            title.setText(html("Bot: identificando " + list.size() + " imágenes..."));
            bubble.add(gap(6));
            bubble.add(text("Imágenes resueltas por cada nodo", 12f, Font.BOLD, MUTED));
            bubble.add(bars);
            bubble.add(gap(4));
            bubble.add(classes);
            bubble.add(gap(8));
            for (int i = 0; i < Math.min(GRID, list.size()); i++) {
                Cell c = new Cell(list.get(i));
                cells.add(c);
                grid.add(c);
            }
            bubble.add(grid);
            if (list.size() > GRID) {
                more = text("y " + (list.size() - GRID) + " más (se ven todas al terminar)", 12f, Font.PLAIN, MUTED);
                bubble.add(more);
            }
        }

        void arrived(Arrived a) {
            Item it = items.get(a.index);
            it.result = a.result;
            it.error = a.error;
            arrived++;
            if (a.result != null) {
                String n = nodeName(a.result.nodeId, a.result.nodeLang);
                nodes.put(a.result.nodeId, n);
                bars.add(n, nodeColor(a.result.nodeId));
                String cls = pretty(a.result.label);
                Integer c = perClass.get(cls);
                perClass.put(cls, c == null ? 1 : c + 1);
                if (it.truth != null) {
                    withTruth++;
                    if (it.truth.equals(a.result.label)) {
                        hits++;
                    }
                }
            } else {
                errors++;
            }
            if (a.index < cells.size()) {
                cells.get(a.index).repaint();
            }
        }

        void refresh() {
            if (items == null) {
                return;
            }
            if (!finished) {
                progress.setText(html(arrived + " de " + items.size() + " listas" + (errors > 0 ? " · " + errors
                        + " con error" : "")));
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : perClass.entrySet()) {
                sb.append(sb.length() == 0 ? "" : " · ").append(e.getKey()).append(" ").append(e.getValue());
            }
            classes.setText(html(sb.toString()));
            bubble.revalidate();
            bubble.repaint();
        }

        void finish(ServerConnection.BatchSummary s, long ms) {
            finished = true;
            title.setText(html(String.format(Locale.ROOT, "Bot: listo, %d imágenes en %.2f s", s.images, ms / 1000.0)));
            String line = String.format(Locale.ROOT, "%d nodo(s) trabajaron a la vez · %.0f imágenes por segundo",
                    nodes.size(), s.images * 1000.0 / Math.max(1, ms));
            if (withTruth > 0) {
                line += String.format(Locale.ROOT, " · acierto %.0f %% (%d de %d)", 100.0 * hits / withTruth, hits, withTruth);
            }
            progress.setText(html(line));
            if (items != null && items.size() > GRID) {
                more.setText(html("y " + (items.size() - GRID) + " más"));
                JButton all = new FlatButton("Ver las " + items.size() + " imágenes", new Color(0xE8EEF8), NAVY);
                all.setAlignmentX(Component.LEFT_ALIGNMENT);
                all.addActionListener(e -> showAll(items));
                bubble.add(gap(8));
                bubble.add(all);
            }
            refresh();
        }

        void fail(String msg) {
            title.setText(html("Bot: error: " + msg));
            title.setForeground(BAD);
            refresh();
        }
    }

    private void showAll(List<Item> items) {
        JPanel all = new JPanel(new GridLayout(0, 8, 6, 6));
        all.setBackground(Color.WHITE);
        all.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (Item it : items) {
            all.add(new Cell(it));
        }
        JScrollPane sp = new JScrollPane(all);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.setPreferredSize(new Dimension(640, 560));
        JDialog d = new JDialog(frame, "Resultados del lote (" + items.size() + " imágenes)", false);
        d.getContentPane().add(sp);
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ arrastrar y pegar

    private final class DropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || s.isDataFlavorSupported(DataFlavor.imageFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            try {
                Transferable t = s.getTransferable();
                if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    handleFiles((List<File>) t.getTransferData(DataFlavor.javaFileListFlavor), null);
                    return true;
                }
                if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    identifyImage((Image) t.getTransferData(DataFlavor.imageFlavor), "imagen arrastrada");
                    return true;
                }
            } catch (Exception ex) {
                setStatus("No se pudo usar lo que arrastraste: " + ex.getMessage());
            }
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void pasteFromClipboard() {
        if (busy) {
            return;
        }
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                identifyImage((Image) cb.getData(DataFlavor.imageFlavor), "imagen pegada");
            } else if (cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                handleFiles((List<File>) cb.getData(DataFlavor.javaFileListFlavor), null);
            } else {
                setStatus("El portapapeles no tiene una imagen.");
            }
        } catch (Exception ex) {
            setStatus("No se pudo pegar: " + ex.getMessage());
        }
    }

    private void identifyImage(Image image, String name) {
        if (busy) {
            setStatus("Espera a que termine la consulta anterior.");
            return;
        }
        BufferedImage img = new BufferedImage(Math.max(1, image.getWidth(null)), Math.max(1, image.getHeight(null)),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(image, 0, 0, Color.WHITE, null);
        g.dispose();
        identifyOne(Msg.FORMAT_RAW, ImagePrep.toCifar(img), thumbnail(img, 160), name, null);
    }

    // ------------------------------------------------------------------ etiquetas reales

    private String sampleTruth(int idx) {
        int y = samples.label(idx);
        return y < samples.classNames.size() ? samples.classNames.get(y) : null;
    }

    /**
     * Si la imagen está en una carpeta con el nombre de una clase (por ejemplo fotos/gato/1.jpg o
     * cifar/cat/1.jpg), se toma como etiqueta real y el chat muestra si acertó.
     */
    private String truthFromPath(File f) {
        File parent = f.getParentFile();
        if (parent == null) {
            return null;
        }
        String n = Normalizer.normalize(parent.getName(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).trim();
        Map<String, String> synonyms = new HashMap<String, String>();
        synonyms.put("airplane", "avion");
        synonyms.put("airplanes", "avion");
        synonyms.put("plane", "avion");
        synonyms.put("aviones", "avion");
        synonyms.put("automobile", "automovil");
        synonyms.put("car", "automovil");
        synonyms.put("cars", "automovil");
        synonyms.put("auto", "automovil");
        synonyms.put("autos", "automovil");
        synonyms.put("automoviles", "automovil");
        synonyms.put("cat", "gato");
        synonyms.put("cats", "gato");
        synonyms.put("gatos", "gato");
        synonyms.put("ship", "barco");
        synonyms.put("ships", "barco");
        synonyms.put("boat", "barco");
        synonyms.put("barcos", "barco");
        String key = synonyms.containsKey(n) ? synonyms.get(n) : n;
        return info.classes.contains(key) ? key : null;
    }

    // ------------------------------------------------------------------ historial y demo

    private void showHistory() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return conn.history();
            }

            @Override
            protected void done() {
                try {
                    List<String> h = get();
                    StringBuilder sb = new StringBuilder();
                    for (String s : h) {
                        sb.append(s).append('\n');
                    }
                    JTextArea area = new JTextArea(h.isEmpty() ? "(sin consultas todavía)" : sb.toString());
                    area.setEditable(false);
                    area.setFont(area.getFont().deriveFont(13f));
                    area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                    JScrollPane sp = new JScrollPane(area);
                    sp.setPreferredSize(new Dimension(360, 360));
                    JOptionPane.showMessageDialog(frame, sp, "Historial guardado en el servidor (" + h.size() / 2
                            + " consultas)", JOptionPane.PLAIN_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "No se pudo obtener el historial: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void runDemo(final int remaining, final int batch) {
        if (remaining <= 0 && batch <= 0) {
            return;
        }
        if (!busy) {
            if (remaining > 0) {
                identifySample();
                scheduleDemo(remaining - 1, batch);
            } else {
                identifySampleBatch(batch);
            }
            return;
        }
        scheduleDemo(remaining, batch);
    }

    private void scheduleDemo(final int remaining, final int batch) {
        javax.swing.Timer t = new javax.swing.Timer(700, e -> runDemo(remaining, batch));
        t.setRepeats(false);
        t.start();
    }

    // ------------------------------------------------------------------ componentes dibujados

    /** Panel de mensajes que se ajusta al ancho de la ventana (sin barra horizontal). */
    private static final class MessagesPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height - 40;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Globo de chat con esquinas redondeadas. */
    private static final class Bubble extends JPanel {
        private final Color bg;

        Bubble(Color bg) {
            this.bg = bg;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 18));
            g2.fillRoundRect(1, 2, getWidth() - 2, getHeight() - 2, 18, 18);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 3, 18, 18);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Botón plano con esquinas redondeadas. */
    private static final class FlatButton extends JButton {
        private final Color bg;
        private boolean hover;

        FlatButton(String label, Color bg, Color fg) {
            super(label);
            this.bg = bg;
            setForeground(fg);
            setFont(getFont().deriveFont(Font.BOLD, 13f));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = bg;
            if (!isEnabled()) {
                c = new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), Math.max(30, bg.getAlpha() / 3));
            } else if (getModel().isPressed()) {
                c = bg.darker();
            } else if (hover) {
                c = bg.getAlpha() < 255 ? new Color(255, 255, 255, Math.min(255, bg.getAlpha() + 40)) : bg.brighter();
            }
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Barra de confianza de una respuesta, del color del nodo que la calculó. */
    private static final class ConfidenceBar extends JComponent {
        private final float value;
        private final Color color;

        ConfidenceBar(float value, Color color) {
            this.value = value;
            this.color = color;
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(220, 14));
            setMaximumSize(new Dimension(220, 14));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xE6EAF0));
            g2.fillRoundRect(0, 4, getWidth(), 8, 8, 8);
            g2.setColor(color);
            g2.fillRoundRect(0, 4, Math.max(8, Math.round(getWidth() * value)), 8, 8, 8);
            g2.dispose();
        }
    }

    /** Barras horizontales: cuántas imágenes del lote resolvió cada nodo. */
    private static final class NodeBars extends JComponent {
        private final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        private final Map<String, Color> colors = new HashMap<String, Color>();

        NodeBars() {
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void add(String node, Color c) {
            Integer n = counts.get(node);
            counts.put(node, n == null ? 1 : n + 1);
            colors.put(node, c);
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(380, Math.max(1, counts.size()) * 24 + 4);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont().deriveFont(12f));
            FontMetrics fm = g2.getFontMetrics();
            if (counts.isEmpty()) {
                g2.setColor(MUTED);
                g2.drawString("esperando a los nodos...", 0, 16);
                g2.dispose();
                return;
            }
            int max = 1;
            for (int v : counts.values()) {
                max = Math.max(max, v);
            }
            int labelW = 150, barW = getWidth() - labelW - 50, y = 2;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                Color c = colors.get(e.getKey());
                g2.setColor(c);
                g2.fillOval(0, y + 6, 9, 9);
                g2.setColor(TEXT);
                g2.drawString(e.getKey(), 14, y + 15);
                int w = Math.max(4, barW * e.getValue() / max);
                g2.setColor(c);
                g2.fillRoundRect(labelW, y + 5, w, 12, 6, 6);
                g2.setColor(TEXT);
                String n = String.valueOf(e.getValue());
                g2.drawString(n, labelW + w + 6, y + 15);
                y += 24;
            }
            g2.dispose();
        }
    }

    /** Miniatura de un lote con su respuesta y una franja del color del nodo que la calculó. */
    private final class Cell extends JComponent {
        private final Item item;

        Cell(Item item) {
            this.item = item;
            setPreferredSize(new Dimension(68, 92));
            setToolTipText(item.name);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int side = 56, x = (getWidth() - side) / 2;
            if (item.thumb != null) {
                int tw = item.thumb.getWidth(), th = item.thumb.getHeight();
                g2.drawImage(item.thumb, x + (side - tw) / 2, (side - th) / 2, null);
            }
            ServerConnection.Result r = item.result;
            if (r != null) {
                g2.setColor(nodeColor(r.nodeId));
                g2.fillRect(x, side + 1, side, 4);
                boolean known = item.truth != null;
                boolean hit = known && item.truth.equals(r.label);
                g2.setColor(known ? (hit ? OK : BAD) : TEXT);
                g2.setFont(getFont().deriveFont(Font.BOLD, 11f));
                drawCentered(g2, pretty(r.label), side + 18);
                g2.setFont(getFont().deriveFont(10f));
                g2.setColor(MUTED);
                drawCentered(g2, "nodo " + r.nodeId, side + 31);
                if (known && !hit) {
                    g2.setColor(BAD);
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRect(x, 0, side - 1, side - 1);
                }
            } else {
                g2.setFont(getFont().deriveFont(11f));
                g2.setColor(item.error != null ? BAD : MUTED);
                drawCentered(g2, item.error != null ? "error" : "...", side + 18);
            }
            g2.dispose();
        }

        private void drawCentered(Graphics2D g2, String s, int y) {
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, y);
        }
    }

    // ------------------------------------------------------------------ inicio

    public static void main(String[] a) throws Exception {
        final Args args = new Args(a);
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // se usa el look and feel por defecto
        }
        String host = args.get("host", "127.0.0.1");
        String port = String.valueOf(args.getInt("port", Msg.CLIENT_PORT));
        String user = args.get("user", "");
        if (!args.has("user")) {
            JTextField hostField = new JTextField(host);
            JTextField portField = new JTextField(port);
            JTextField userField = new JTextField(System.getProperty("user.name", "usuario"));
            JPanel p = new JPanel(new GridLayout(0, 1, 2, 2));
            p.add(new JLabel("Servidor"));
            p.add(hostField);
            p.add(new JLabel("Puerto"));
            p.add(portField);
            p.add(new JLabel("Usuario"));
            p.add(userField);
            int opt = JOptionPane.showConfirmDialog(null, p, "Open AI Cubes - Iniciar sesión", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (opt != JOptionPane.OK_OPTION) {
                System.exit(0);
            }
            host = hostField.getText().trim();
            port = portField.getText().trim();
            user = userField.getText().trim();
        }
        final ServerConnection conn;
        final ServerConnection.LoginInfo info;
        try {
            conn = new ServerConnection(host, Integer.parseInt(port));
            info = conn.login(user);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "No se pudo conectar con " + host + ":" + port + "\n" + e.getMessage(),
                    "Open AI Cubes", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        File testFile = new File(args.get("test", "data/test.bin"));
        final Dataset samples = testFile.exists() ? Dataset.load(testFile) : null;
        final String u = user;
        final int demo = args.getInt("demo", 0);
        windowHeight = args.getInt("alto", 780);
        final int demoBatch = args.getInt("demo-batch", 0);
        final String demoFolder = args.get("demo-folder", null);
        SwingUtilities.invokeLater(() -> {
            ChatClient c = new ChatClient(conn, info, u, samples);
            c.buildUi();
            // --demo N y --demo-batch N: envían imágenes de prueba al abrir (útil para capturas del informe)
            // --demo-folder RUTA: identifica esa carpeta (o imagen) al abrir
            if (demoFolder != null) {
                File dir = new File(demoFolder);
                c.handleFiles(Collections.singletonList(dir), dir.getName());
            }
            if ((demo > 0 || demoBatch > 0) && samples != null) {
                c.runDemo(demo, demoBatch);
            }
        });
    }
}
