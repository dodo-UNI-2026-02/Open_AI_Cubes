package cubes.cnn;

/**
 * Descenso por gradiente estocástico con momentum:
 *   v = momentum * v + g / n
 *   w = w - lr * v
 * donde g es la SUMA de gradientes de las n imágenes del lote.
 */
public final class Sgd {

    private final float lr;
    private final float momentum;
    private float[] velocity;

    public Sgd(float lr, float momentum) {
        this.lr = lr;
        this.momentum = momentum;
    }

    public void step(Params params, float[] gradSum, int n) {
        float[] w = params.data;
        if (velocity == null) {
            velocity = new float[w.length];
        }
        float inv = 1f / n;
        for (int i = 0; i < w.length; i++) {
            velocity[i] = momentum * velocity[i] + gradSum[i] * inv;
            w[i] -= lr * velocity[i];
        }
    }
}
