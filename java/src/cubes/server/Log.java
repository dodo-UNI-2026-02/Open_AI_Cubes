package cubes.server;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Registro simple con hora, en la consola del servidor. */
public final class Log {

    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");
    private volatile boolean verbose;

    public void setVerbose(boolean v) {
        verbose = v;
    }

    public synchronized void info(String msg) {
        System.out.println("[" + fmt.format(new Date()) + "] " + msg);
    }

    public void debug(String msg) {
        if (verbose) {
            info(msg);
        }
    }
}
