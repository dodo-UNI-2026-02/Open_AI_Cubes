package cubes.server;

import cubes.cnn.Params;
import java.util.List;

/** Pesos "de servicio" que usa el chat, con un número de versión que sube en cada publicación. */
public final class ModelStore {

    public static final class Snapshot {
        public final Params params;
        public final int version;

        Snapshot(Params params, int version) {
            this.params = params;
            this.version = version;
        }
    }

    private volatile Snapshot current;
    private final List<String> classNames;

    public ModelStore(Params initial, List<String> classNames) {
        this.current = new Snapshot(initial, 1);
        this.classNames = classNames;
    }

    public Snapshot snapshot() {
        return current;
    }

    public synchronized void publish(Params p) {
        current = new Snapshot(p, current.version + 1);
    }

    public List<String> classNames() {
        return classNames;
    }

    public String className(int idx) {
        return idx >= 0 && idx < classNames.size() ? classNames.get(idx) : ("clase " + idx);
    }
}
