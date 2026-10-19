package cubes.tools;

import cubes.cnn.ChunkExecutor;
import cubes.cnn.Cnn;
import cubes.cnn.Dataset;
import cubes.cnn.Params;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Genera un archivo de referencia para comprobar que los nodos Python y Go calculan
 * exactamente lo mismo que Java (ver docs/CNN_SPEC.md, sección "Archivo de referencia").
 *
 * Uso: java cubes.tools.RefDump data/train.bin data/init_weights.bin 8 data/ref.bin
 */
public final class RefDump {

    public static void main(String[] a) throws IOException {
        Dataset ds = Dataset.load(new File(a[0]));
        Params p = Params.load(new File(a[1]));
        int n = Integer.parseInt(a[2]);
        float[][] x = new float[n][];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = ds.image(i);
            y[i] = ds.label(i);
        }
        Cnn cnn = new Cnn(new ChunkExecutor(1));
        float[] g = new float[p.data.length];
        Cnn.Stats st = cnn.forwardBackward(p, x, y, g);
        float[][] probs = cnn.predict(p, x);

        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[3])));
        try {
            out.writeInt(0x4F435231); // "OCR1"
            out.writeInt(n);
            out.writeDouble(st.lossSum);
            out.writeInt(st.correct);
            out.writeInt(g.length);
            for (float v : g) {
                out.writeFloat(v);
            }
            out.writeInt(p.classes);
            for (float[] row : probs) {
                for (float v : row) {
                    out.writeFloat(v);
                }
            }
        } finally {
            out.close();
        }
        System.out.println("Referencia: n=" + n + " perdida=" + st.lossSum + " aciertos=" + st.correct);
        System.exit(0);
    }
}
