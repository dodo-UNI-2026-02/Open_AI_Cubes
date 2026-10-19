package cubes.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Lee payloads en big-endian (ByteBuffer usa big-endian por defecto). */
public final class PayloadReader {

    private final ByteBuffer bb;

    public PayloadReader(byte[] payload) {
        this.bb = ByteBuffer.wrap(payload);
    }

    public int u8() {
        return bb.get() & 0xFF;
    }

    public int i32() {
        return bb.getInt();
    }

    public float f32() {
        return bb.getFloat();
    }

    public double f64() {
        return bb.getDouble();
    }

    public String str() {
        int len = bb.getShort() & 0xFFFF;
        byte[] b = new byte[len];
        bb.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public float[] floats() {
        int n = bb.getInt();
        float[] v = new float[n];
        bb.asFloatBuffer().get(v);
        bb.position(bb.position() + n * 4);
        return v;
    }

    public float[] floats(int n) {
        float[] v = new float[n];
        bb.asFloatBuffer().get(v);
        bb.position(bb.position() + n * 4);
        return v;
    }

    public byte[] bytes(int n) {
        byte[] b = new byte[n];
        bb.get(b);
        return b;
    }

    public int remaining() {
        return bb.remaining();
    }
}
