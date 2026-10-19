package cubes.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Genera las gráficas del informe (PNG) a partir de los CSV de resultados, usando solo Java2D.
 *
 * Uso: java -cp out cubes.tools.Charts results
 * Lee results/local.csv, results/distribuido.csv y results/carga.csv (los que existan) y escribe
 * matriz_tiempos.png, escalabilidad_tiempo.png, escalabilidad_speedup.png, carga_latencia.png,
 * carga_throughput.png en la misma carpeta.
 */
public final class Charts {

    private static final Color[] PALETTE = {
        new Color(0x2F6FDE), new Color(0xE0802B), new Color(0x2E9E5B), new Color(0xC23B4E),
        new Color(0x7B4FD0), new Color(0x8C6D31), new Color(0x1AA3B5), new Color(0x777777)};

    // ------------------------------------------------------------------ CSV

    static List<Map<String, String>> readCsv(File f) throws IOException {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        if (!f.exists()) {
            return rows;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            String[] header = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (header == null) {
                    header = parts;
                    continue;
                }
                if (parts[0].equals(header[0])) {
                    continue; // cabecera repetida
                }
                Map<String, String> row = new HashMap<String, String>();
                for (int i = 0; i < header.length && i < parts.length; i++) {
                    row.put(header[i].trim(), parts[i].trim());
                }
                rows.add(row);
            }
        } finally {
            r.close();
        }
        return rows;
    }

    static double num(Map<String, String> row, String key) {
        try {
            return Double.parseDouble(row.get(key));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------------ gráfica genérica

    static final class Series {
        final String name;
        final List<double[]> points = new ArrayList<double[]>();

        Series(String name) {
            this.name = name;
        }
    }

    /** Gráfica de líneas con ejes, cuadrícula y leyenda. logY usa escala logarítmica en Y. */
    static void lineChart(File out, String title, String xLabel, String yLabel, List<Series> series, boolean logY,
                          boolean identityLine) throws IOException {
        int w = 960, h = 560, left = 90, right = 220, top = 60, bottom = 70;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        prepare(g, w, h, title);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Series s : series) {
            for (double[] p : s.points) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        if (minX == Double.MAX_VALUE) {
            g.drawString("Sin datos", w / 2 - 30, h / 2);
            save(img, out);
            return;
        }
        if (identityLine) {
            maxY = Math.max(maxY, maxX);
            minY = Math.min(minY, minX);
        }
        if (!logY) {
            minY = Math.min(0, minY);
            maxY = niceMax(maxY);
        } else {
            minY = Math.pow(10, Math.floor(Math.log10(Math.max(1e-9, minY))));
            maxY = Math.pow(10, Math.ceil(Math.log10(Math.max(1e-9, maxY))));
        }
        if (maxX == minX) {
            maxX = minX + 1;
        }
        Axis ax = new Axis(left, top, w - left - right, h - top - bottom, minX, maxX, minY, maxY, logY);
        ax.draw(g, xLabel, yLabel);
        if (identityLine) {
            g.setColor(new Color(0xBBBBBB));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {6f, 5f}, 0f));
            g.drawLine(ax.x(minX), ax.y(minX), ax.x(maxX), ax.y(maxX));
            g.drawString("ideal (lineal)", ax.x(maxX) - 90, ax.y(maxX) + 18);
        }
        int i = 0;
        for (Series s : series) {
            Color c = PALETTE[i % PALETTE.length];
            g.setColor(c);
            g.setStroke(new BasicStroke(2.5f));
            double[] prev = null;
            for (double[] p : s.points) {
                if (prev != null) {
                    g.drawLine(ax.x(prev[0]), ax.y(prev[1]), ax.x(p[0]), ax.y(p[1]));
                }
                g.fillOval(ax.x(p[0]) - 4, ax.y(p[1]) - 4, 8, 8);
                prev = p;
            }
            legend(g, w - right + 10, top + 10 + i * 22, c, s.name);
            i++;
        }
        save(img, out);
    }

    /** Barras agrupadas: categorías en X, una barra por serie, valores anotados. */
    static void barChart(File out, String title, String yLabel, List<String> categories,
                         Map<String, Map<String, Double>> seriesValues, boolean logY, String unitFmt) throws IOException {
        int w = 1040, h = 580, left = 90, right = 230, top = 60, bottom = 80;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        prepare(g, w, h, title);
        double maxY = 0, minY = Double.MAX_VALUE;
        for (Map<String, Double> m : seriesValues.values()) {
            for (Double v : m.values()) {
                if (v != null && !v.isNaN()) {
                    maxY = Math.max(maxY, v);
                    minY = Math.min(minY, v);
                }
            }
        }
        if (maxY == 0) {
            g.drawString("Sin datos", w / 2 - 30, h / 2);
            save(img, out);
            return;
        }
        double lo = logY ? Math.pow(10, Math.floor(Math.log10(minY))) : 0;
        double hi = logY ? Math.pow(10, Math.ceil(Math.log10(maxY))) : niceMax(maxY);
        Axis ax = new Axis(left, top, w - left - right, h - top - bottom, 0, categories.size(), lo, hi, logY);
        ax.drawY(g, yLabel);
        int groupW = ax.pw / Math.max(1, categories.size());
        int nSeries = seriesValues.size();
        int barW = Math.max(6, (int) (groupW * 0.75 / Math.max(1, nSeries)));
        g.setFont(g.getFont().deriveFont(13f));
        FontMetrics fm = g.getFontMetrics();
        for (int ci = 0; ci < categories.size(); ci++) {
            int gx = ax.px + ci * groupW + (groupW - barW * nSeries) / 2;
            String cat = categories.get(ci);
            g.setColor(Color.DARK_GRAY);
            g.drawString(cat, ax.px + ci * groupW + (groupW - fm.stringWidth(cat)) / 2, ax.py + ax.ph + 22);
            int si = 0;
            for (Map.Entry<String, Map<String, Double>> e : seriesValues.entrySet()) {
                Double v = e.getValue().get(cat);
                if (v != null && !v.isNaN() && v > 0) {
                    int y = ax.y(v);
                    g.setColor(PALETTE[si % PALETTE.length]);
                    g.fillRect(gx + si * barW, y, barW - 3, ax.py + ax.ph - y);
                    g.setColor(Color.DARK_GRAY);
                    g.setFont(g.getFont().deriveFont(10f));
                    String txt = String.format(Locale.ROOT, unitFmt, v);
                    g.drawString(txt, gx + si * barW + (barW - 3 - g.getFontMetrics().stringWidth(txt)) / 2, y - 4);
                    g.setFont(g.getFont().deriveFont(13f));
                }
                si++;
            }
        }
        int si = 0;
        for (String s : seriesValues.keySet()) {
            legend(g, w - right + 10, top + 10 + si * 22, PALETTE[si % PALETTE.length], s);
            si++;
        }
        save(img, out);
    }

    private static void prepare(Graphics2D g, int w, int h, String title) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x222222));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString(title, 24, 34);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    }

    private static void legend(Graphics2D g, int x, int y, Color c, String text) {
        g.setColor(c);
        g.fillRect(x, y - 10, 14, 14);
        g.setColor(new Color(0x333333));
        g.drawString(text, x + 20, y + 2);
    }

    private static double niceMax(double v) {
        if (v <= 0) {
            return 1;
        }
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        for (double m : new double[] {1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) {
            if (m * p >= v * 1.05) {
                return m * p;
            }
        }
        return 10 * p;
    }

    private static void save(BufferedImage img, File out) throws IOException {
        ImageIO.write(img, "png", out);
        System.out.println("  " + out.getPath());
    }

    /** Ejes cartesianos con cuadrícula. */
    static final class Axis {
        final int px, py, pw, ph;
        final double minX, maxX, minY, maxY;
        final boolean logY;

        Axis(int px, int py, int pw, int ph, double minX, double maxX, double minY, double maxY, boolean logY) {
            this.px = px;
            this.py = py;
            this.pw = pw;
            this.ph = ph;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.logY = logY;
        }

        int x(double v) {
            return px + (int) Math.round((v - minX) / (maxX - minX) * pw);
        }

        int y(double v) {
            double t = logY ? (Math.log10(Math.max(v, 1e-12)) - Math.log10(minY)) / (Math.log10(maxY) - Math.log10(minY))
                    : (v - minY) / (maxY - minY);
            return py + ph - (int) Math.round(t * ph);
        }

        void drawY(Graphics2D g, String yLabel) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            List<Double> ticks = new ArrayList<Double>();
            if (logY) {
                for (double v = minY; v <= maxY * 1.0001; v *= 10) {
                    ticks.add(v);
                }
            } else {
                for (int i = 0; i <= 5; i++) {
                    ticks.add(minY + (maxY - minY) * i / 5);
                }
            }
            for (double t : ticks) {
                int yy = y(t);
                g.setColor(new Color(0xE6E6E6));
                g.drawLine(px, yy, px + pw, yy);
                g.setColor(new Color(0x555555));
                String s = fmt(t);
                g.drawString(s, px - 8 - g.getFontMetrics().stringWidth(s), yy + 4);
            }
            g.setColor(new Color(0x444444));
            g.drawLine(px, py, px, py + ph);
            g.drawLine(px, py + ph, px + pw, py + ph);
            Graphics2D r = (Graphics2D) g.create();
            r.rotate(-Math.PI / 2);
            r.drawString(yLabel, -(py + ph / 2) - r.getFontMetrics().stringWidth(yLabel) / 2, 24);
            r.dispose();
        }

        void draw(Graphics2D g, String xLabel, String yLabel) {
            drawY(g, yLabel);
            List<Double> ticks = new ArrayList<Double>();
            double step = niceStep((maxX - minX) / 8);
            for (double v = Math.ceil(minX / step) * step; v <= maxX + 1e-9; v += step) {
                ticks.add(v);
            }
            for (double t : ticks) {
                int xx = x(t);
                g.setColor(new Color(0xF0F0F0));
                g.drawLine(xx, py, xx, py + ph);
                g.setColor(new Color(0x555555));
                String s = fmt(t);
                g.drawString(s, xx - g.getFontMetrics().stringWidth(s) / 2, py + ph + 18);
            }
            g.drawString(xLabel, px + pw / 2 - g.getFontMetrics().stringWidth(xLabel) / 2, py + ph + 44);
        }

        static double niceStep(double raw) {
            if (raw <= 0) {
                return 1;
            }
            double p = Math.pow(10, Math.floor(Math.log10(raw)));
            for (double m : new double[] {1, 2, 5, 10}) {
                if (m * p >= raw) {
                    return Math.max(1, m * p);
                }
            }
            return 10 * p;
        }

        static String fmt(double v) {
            if (Math.abs(v - Math.round(v)) < 1e-9) {
                return String.valueOf(Math.round(v));
            }
            return String.format(Locale.ROOT, Math.abs(v) < 1 ? "%.2f" : "%.1f", v);
        }
    }

    // ------------------------------------------------------------------ gráficas del proyecto

    public static void main(String[] a) throws IOException {
        File dir = new File(a.length > 0 ? a[0] : "results");
        List<Map<String, String>> local = readCsv(new File(dir, "local.csv"));
        List<Map<String, String>> dist = readCsv(new File(dir, "distribuido.csv"));
        List<Map<String, String>> load = readCsv(new File(dir, "carga.csv"));
        System.out.println("Graficas generadas:");

        // 1) Matriz de 12 casos: tiempo por escenario y lenguaje (se toma la última corrida de cada
        //    combinación; en los escenarios distribuidos, la de más máquinas).
        List<String> cats = new ArrayList<String>();
        cats.add("secuencial");
        cats.add("paralelo");
        cats.add("distribuido");
        cats.add("paralelo_distribuido");
        Map<String, Map<String, Double>> values = new LinkedHashMap<String, Map<String, Double>>();
        Map<String, Double> bestMachines = new HashMap<String, Double>();
        List<Map<String, String>> all = new ArrayList<Map<String, String>>(local);
        all.addAll(dist);
        for (Map<String, String> r : all) {
            String esc = r.get("escenario");
            if (!cats.contains(esc)) {
                continue;
            }
            String rawLang = r.get("lenguaje") == null ? "" : r.get("lenguaje").toLowerCase();
            boolean distributed = esc.contains("distribuido");
            // En los escenarios distribuidos el Caso 3 es el clúster mixto; en los locales, el nodo Go.
            if (distributed && rawLang.equals("go")) {
                continue;
            }
            String lang = caseName(rawLang);
            double m = num(r, "maquinas");
            String key = esc + "|" + lang;
            Double prev = bestMachines.get(key);
            if (prev != null && m < prev) {
                continue;
            }
            bestMachines.put(key, m);
            if (!values.containsKey(lang)) {
                values.put(lang, new HashMap<String, Double>());
            }
            values.get(lang).put(esc, num(r, "segundos"));
        }
        barChart(new File(dir, "matriz_tiempos.png"), "Tiempo de entrenamiento por escenario y caso (menos es mejor)",
                "segundos (escala log)", cats, sortByName(values), true, "%.1f s");

        // 2) Escalabilidad: tiempo vs. número de nodos y speedup = T_secuencial / T, donde T_secuencial
        //    es la corrida local de 1 hilo del mismo lenguaje (para el clúster mixto, la de Java).
        Map<String, Double> seq = new HashMap<String, Double>();
        for (Map<String, String> r : local) {
            if ("secuencial".equals(r.get("escenario"))) {
                seq.put(r.get("lenguaje").toLowerCase(), num(r, "segundos"));
            }
        }
        Map<String, TreeMap<Double, Double>> byKey = new TreeMap<String, TreeMap<Double, Double>>();
        Map<String, Double> baseOf = new HashMap<String, Double>();
        for (Map<String, String> r : dist) {
            String lang = r.get("lenguaje").toLowerCase();
            String key = r.get("escenario").replace("_", " ") + " / " + seriesName(lang);
            if (!byKey.containsKey(key)) {
                byKey.put(key, new TreeMap<Double, Double>());
            }
            // eje X = núcleos usados en total (nodos × hilos por nodo)
            byKey.get(key).put(num(r, "maquinas") * num(r, "hilos"), num(r, "segundos"));
            Double b = seq.get(lang.contains("+") ? "java" : lang);
            baseOf.put(key, b == null ? Double.NaN : b);
        }
        List<Series> time = new ArrayList<Series>();
        List<Series> speed = new ArrayList<Series>();
        for (Map.Entry<String, TreeMap<Double, Double>> e : byKey.entrySet()) {
            Series st = new Series(e.getKey());
            Series ss = new Series(e.getKey());
            double base = baseOf.get(e.getKey());
            for (Map.Entry<Double, Double> p : e.getValue().entrySet()) {
                st.points.add(new double[] {p.getKey(), p.getValue()});
                if (!Double.isNaN(base)) {
                    ss.points.add(new double[] {p.getKey(), base / p.getValue()});
                }
            }
            time.add(st);
            speed.add(ss);
        }
        lineChart(new File(dir, "escalabilidad_tiempo.png"), "Tiempo vs. nucleos usados", "nucleos usados (nodos x hilos)",
                "segundos", time, false, false);
        lineChart(new File(dir, "escalabilidad_speedup.png"), "Speedup = T secuencial / T (mismo lenguaje con 1 hilo)",
                "nucleos usados (nodos x hilos)", "speedup", speed, false, true);

        // 3) Pruebas de carga
        Map<String, Series> lat = new LinkedHashMap<String, Series>();
        Map<String, Series> thr = new LinkedHashMap<String, Series>();
        for (Map<String, String> r : load) {
            String label = r.get("etiqueta");
            for (String p : new String[] {"p50_ms", "p95_ms", "p99_ms"}) {
                String k = label + " " + p.replace("_ms", "");
                if (!lat.containsKey(k)) {
                    lat.put(k, new Series(k));
                }
                lat.get(k).points.add(new double[] {num(r, "clientes"), num(r, p)});
            }
            if (!thr.containsKey(label)) {
                thr.put(label, new Series(label));
            }
            thr.get(label).points.add(new double[] {num(r, "clientes"), num(r, "resp_por_s")});
        }
        sortPoints(lat.values());
        sortPoints(thr.values());
        lineChart(new File(dir, "carga_latencia.png"), "Latencia de respuesta vs. clientes simultaneos",
                "clientes simultaneos", "latencia (ms)", new ArrayList<Series>(lat.values()), false, false);
        lineChart(new File(dir, "carga_throughput.png"), "Respuestas por segundo vs. clientes simultaneos",
                "clientes simultaneos", "respuestas / s", new ArrayList<Series>(thr.values()), false, false);
    }

    private static void sortPoints(Iterable<Series> series) {
        for (Series s : series) {
            s.points.sort((p, q) -> Double.compare(p[0], q[0]));
        }
    }

    private static Map<String, Map<String, Double>> sortByName(Map<String, Map<String, Double>> m) {
        return new TreeMap<String, Map<String, Double>>(m);
    }

    /** Nombre corto de una serie de escalabilidad según los lenguajes de los nodos. */
    static String seriesName(String lang) {
        if (lang.contains("+")) {
            return "mixto (C3)";
        }
        if (lang.equals("java")) {
            return "Java (C1)";
        }
        if (lang.equals("python")) {
            return "Python (C2)";
        }
        if (lang.equals("go")) {
            return "Go (C3)";
        }
        return lang;
    }

    /** Nombre del caso según el lenguaje de los nodos (ver la matriz de evaluación del plan). */
    static String caseName(String lang) {
        if (lang == null) {
            return "?";
        }
        String l = lang.toLowerCase();
        if (l.contains("+") || l.equals("go")) {
            return "Caso 3: Go / mixto";
        }
        if (l.equals("java")) {
            return "Caso 1: Java";
        }
        if (l.equals("python")) {
            return "Caso 2: Python";
        }
        return l;
    }
}
