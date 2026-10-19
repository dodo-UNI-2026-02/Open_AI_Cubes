package cubes.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Módulo Backend: consume los resultados del modelo y responde a los clientes.
 * Lleva las sesiones (usuario → idClientChat), el historial del chat y las estadísticas.
 * El historial también se agrega a un archivo de texto (la "base de datos" del chat).
 */
public final class Backend {

    public static final class Session {
        public final String user;
        public final int idClientChat;
        final List<String> history = Collections.synchronizedList(new ArrayList<String>());

        Session(String user, int id) {
            this.user = user;
            this.idClientChat = id;
        }
    }

    private final AtomicInteger nextChatId = new AtomicInteger(812);
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final File historyFile;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    final AtomicLong requests = new AtomicLong();
    final AtomicLong errors = new AtomicLong();
    final AtomicLong latencyMsSum = new AtomicLong();
    final AtomicInteger connectedClients = new AtomicInteger();

    public Backend(File historyFile) {
        this.historyFile = historyFile;
    }

    /** Un mismo usuario conserva su idClientChat e historial entre conexiones. */
    public Session login(String user) {
        Session s = sessions.get(user);
        if (s == null) {
            Session created = new Session(user, nextChatId.getAndIncrement());
            s = sessions.putIfAbsent(user, created);
            if (s == null) {
                s = created;
            }
        }
        return s;
    }

    public void record(Session s, String answer, int nodeId, String lang, long ms) {
        requests.incrementAndGet();
        latencyMsSum.addAndGet(ms);
        s.history.add("Yo: Identificar");
        s.history.add("Bot: " + answer);
        String line = fmt.format(new Date()) + ";" + s.user + ";" + String.format("%04d", s.idClientChat) + ";Identificar;"
                + answer + ";nodo " + nodeId + " (" + lang + ");" + ms + " ms\n";
        synchronized (this) {
            if (historyFile == null) {
                return;
            }
            try {
                Writer w = new OutputStreamWriter(new FileOutputStream(historyFile, true), StandardCharsets.UTF_8);
                try {
                    w.write(line);
                } finally {
                    w.close();
                }
            } catch (IOException e) {
                // El historial en archivo es opcional; la respuesta al cliente no depende de esto.
            }
        }
    }

    public List<String> history(Session s) {
        synchronized (s.history) {
            return new ArrayList<String>(s.history);
        }
    }

    public int sessionCount() {
        return sessions.size();
    }
}
