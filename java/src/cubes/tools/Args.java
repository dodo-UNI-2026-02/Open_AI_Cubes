package cubes.tools;

import java.util.HashMap;
import java.util.Map;

/** Lector mínimo de argumentos con la forma --clave valor. */
public final class Args {

    private final Map<String, String> values = new HashMap<String, String>();

    public Args(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    values.put(key, args[++i]);
                } else {
                    values.put(key, "true");
                }
            }
        }
    }

    public String get(String key, String def) {
        String v = values.get(key);
        return v == null ? def : v;
    }

    public int getInt(String key, int def) {
        String v = values.get(key);
        return v == null ? def : Integer.parseInt(v);
    }

    public float getFloat(String key, float def) {
        String v = values.get(key);
        return v == null ? def : Float.parseFloat(v);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }
}
