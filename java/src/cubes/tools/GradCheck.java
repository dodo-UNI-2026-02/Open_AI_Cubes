package cubes.tools;

import cubes.cnn.ChunkExecutor;
import cubes.cnn.Cnn;
import cubes.cnn.Params;
import java.util.Locale;
import java.util.Random;

/**
 * Verifica el backward comparando el gradiente analítico con el numérico
 * (diferencias centradas) en posiciones al azar de cada capa.
 */
public final class GradCheck {

    public static void main(String[] a) {
        Random r = new Random(1);
        int n = 3;
        float[][] x = new float[n][Params.IN_SIZE];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < Params.IN_SIZE; j++) {
                x[i][j] = (float) (r.nextGaussian() * 0.5);
            }
            y[i] = r.nextInt(4);
        }
        Params p = Params.randomInit(4, 3L);
        Cnn cnn = new Cnn(new ChunkExecutor(2));
        float[] g = new float[p.data.length];
        cnn.forwardBackward(p, x, y, g);

        int[][] ranges = {
            {Params.O_W1, Params.O_B1}, {Params.O_B1, Params.O_W2}, {Params.O_W2, Params.O_B2},
            {Params.O_B2, Params.O_W3}, {Params.O_W3, Params.O_B3}, {Params.O_B3, Params.O_W4},
            {Params.O_W4, p.oB4}, {p.oB4, p.data.length}};
        String[] names = {"W1", "b1", "W2", "b2", "W3", "b3", "W4", "b4"};
        double worst = 0;
        for (int k = 0; k < ranges.length; k++) {
            double maxRel = 0;
            for (int t = 0; t < 6; t++) {
                int idx = ranges[k][0] + r.nextInt(ranges[k][1] - ranges[k][0]);
                float old = p.data[idx];
                float eps = Float.parseFloat(System.getProperty("eps", "1e-3"));
                p.data[idx] = old + eps;
                double lp = loss(cnn, p, x, y);
                p.data[idx] = old - eps;
                double lm = loss(cnn, p, x, y);
                p.data[idx] = old;
                double num = (lp - lm) / (2 * eps);
                double ana = g[idx];
                // Tolerancia absoluta + relativa: la ReLU y el max pooling tienen "quiebres"
                // que hacen ruidosa la derivada numérica en algunas posiciones.
                double err = Math.abs(num - ana) / (0.01 + Math.abs(num));
                maxRel = Math.max(maxRel, err);
                if (System.getProperty("v") != null) {
                    System.out.printf(Locale.ROOT, "   %s[%d] num=%.6f ana=%.6f%n", names[k], idx, num, ana);
                }
            }
            worst = Math.max(worst, maxRel);
            System.out.printf(Locale.ROOT, "%s error max = %.4f%n", names[k], maxRel);
        }
        System.out.println(worst < 0.25 ? "OK" : "REVISAR");
        System.exit(0);
    }

    private static double loss(Cnn cnn, Params p, float[][] x, int[] y) {
        return cnn.forwardBackward(p, x, y, new float[p.data.length]).lossSum;
    }
}
