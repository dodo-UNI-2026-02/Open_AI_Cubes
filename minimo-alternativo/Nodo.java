import java.io.*;
import java.net.Socket;

/**
 * Nodo de cómputo en Java. Recibe pesos e imágenes del servidor y calcula con la CNN,
 * repartiendo cada capa entre sus hilos (micro chunks).
 * Uso: java Nodo [ip_servidor] [hilos]
 */
public class Nodo {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int hilos = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
        Cnn cnn = new Cnn(hilos);
        try (Socket s = new Socket(host, 6000)) {
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            out.writeUTF("java");
            out.writeInt(hilos);
            out.flush();
            System.out.println("Nodo Java conectado a " + host + " con " + hilos + " hilos");
            float[] w = null;
            while (true) {
                byte tipo = in.readByte();
                if (tipo == 'W') {
                    w = new float[in.readInt()];
                    for (int i = 0; i < w.length; i++) w[i] = in.readFloat();
                    continue;
                }
                int n = in.readInt();
                byte[] etiquetas = new byte[tipo == 'T' ? n : 0];
                in.readFully(etiquetas);
                float[][] x = new float[n][];
                byte[] img = new byte[Cnn.IN];
                for (int i = 0; i < n; i++) {
                    in.readFully(img);
                    x[i] = Cnn.normalizar(img, 0);
                }
                if (tipo == 'I') {
                    float[][] probs = cnn.predecir(w, x);
                    out.writeInt(probs[0].length);
                    for (float[] p : probs) for (float v : p) out.writeFloat(v);
                } else {
                    int[] y = new int[n];
                    for (int i = 0; i < n; i++) y[i] = etiquetas[i];
                    float[] g = new float[w.length];
                    double[] r = cnn.entrenarLote(w, x, y, g);
                    for (float v : g) out.writeFloat(v);
                    out.writeDouble(r[0]);
                    out.writeInt((int) r[1]);
                }
                out.flush();
                System.out.println((tipo == 'I' ? "Inferencia" : "Entrenamiento") + ": " + n + " imágenes");
            }
        } catch (EOFException e) {
            System.out.println("El servidor cerró la conexión");
        }
        System.exit(0);
    }
}
