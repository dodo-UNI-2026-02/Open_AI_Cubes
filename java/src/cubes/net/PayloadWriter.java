package cubes.net;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Construye payloads en big-endian con los tipos del protocolo (u8, i32, f32, f64, str, f32[]). */
public final class PayloadWriter {

    private final ByteArrayOutputStream bytes;
    private final DataOutputStream out;

    public PayloadWriter() {
        this(256);
    }

    public PayloadWriter(int capacity) {
        bytes = new ByteArrayOutputStream(capacity);
        out = new DataOutputStream(bytes);
    }

    public PayloadWriter u8(int v) {
        try {
            out.writeByte(v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public PayloadWriter i32(int v) {
        try {
            out.writeInt(v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public PayloadWriter f32(float v) {
        try {
            out.writeFloat(v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public PayloadWriter f64(double v) {
        try {
            out.writeDouble(v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public PayloadWriter str(String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > 65535) {
            throw new IllegalArgumentException("Texto demasiado largo");
        }
        try {
            out.writeShort(b.length);
            out.write(b);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    /** f32[]: cantidad (i32) seguida de los valores. Usa un ByteBuffer para ir rápido. */
    public PayloadWriter floats(float[] v) {
        i32(v.length);
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4);
        bb.asFloatBuffer().put(v);
        return bytes(bb.array(), 0, v.length * 4);
    }

    public PayloadWriter bytes(byte[] b, int off, int len) {
        try {
            out.write(b, off, len);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public byte[] toBytes() {
        return bytes.toByteArray();
    }
}
