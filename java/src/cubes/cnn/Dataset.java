package cubes.cnn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dataset en formato binario CIFAR-10: cada registro es 1 byte de etiqueta seguido de
 * 3072 bytes de píxeles (1024 R, 1024 G, 1024 B, fila por fila). Los nombres de las
 * clases están en classes.txt, en la misma carpeta, una por línea.
 */
public final class Dataset {

    public static final int RECORD = 1 + Params.IN_SIZE;

    public final byte[] raw;
    public final int size;
    public final List<String> classNames;

    private Dataset(byte[] raw, List<String> classNames) {
        this.raw = raw;
        this.size = raw.length / RECORD;
        this.classNames = classNames;
    }

    public static Dataset load(File bin) throws IOException {
        long len = bin.length();
        if (len % RECORD != 0) {
            throw new IOException("Tamaño no múltiplo de " + RECORD + ": " + bin);
        }
        byte[] data = new byte[(int) len];
        FileInputStream in = new FileInputStream(bin);
        try {
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) {
                    throw new IOException("Archivo truncado: " + bin);
                }
                off += r;
            }
        } finally {
            in.close();
        }
        return new Dataset(data, loadClassNames(new File(bin.getAbsoluteFile().getParentFile(), "classes.txt")));
    }

    public static List<String> loadClassNames(File f) throws IOException {
        List<String> names = new ArrayList<String>();
        if (!f.exists()) {
            return names;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    names.add(line.trim());
                }
            }
        } finally {
            r.close();
        }
        return names;
    }

    public int label(int i) {
        return raw[i * RECORD] & 0xFF;
    }

    /** Desplazamiento del primer byte de píxeles de la imagen i dentro de raw. */
    public int pixelOffset(int i) {
        return i * RECORD + 1;
    }

    public float[] image(int i) {
        return Cnn.normalize(raw, pixelOffset(i));
    }

    public int numClasses() {
        if (!classNames.isEmpty()) {
            return classNames.size();
        }
        int max = 0;
        for (int i = 0; i < size; i++) {
            max = Math.max(max, label(i));
        }
        return max + 1;
    }
}
