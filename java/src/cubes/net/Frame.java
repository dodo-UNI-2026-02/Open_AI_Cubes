package cubes.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Trama del protocolo OC/1 (ver docs/PROTOCOLO.md):
 *
 *   'O' 'C' | versión (1 B) | tipo (1 B) | id petición (int32) | longitud (int32) | payload
 *
 * Todo en big-endian, que es el orden por defecto de DataInput/DataOutputStream.
 */
public final class Frame {

    public static final int MAGIC0 = 'O', MAGIC1 = 'C', VERSION = 1;
    public static final int MAX_PAYLOAD = 64 * 1024 * 1024;

    public final int type;
    public final int requestId;
    public final byte[] payload;

    public Frame(int type, int requestId, byte[] payload) {
        this.type = type;
        this.requestId = requestId;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public PayloadReader reader() {
        return new PayloadReader(payload);
    }

    /** Escribe la trama completa. Quien llama debe sincronizar si varios hilos usan el mismo stream. */
    public void write(DataOutputStream out) throws IOException {
        out.writeByte(MAGIC0);
        out.writeByte(MAGIC1);
        out.writeByte(VERSION);
        out.writeByte(type);
        out.writeInt(requestId);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /** Lee una trama; lanza EOFException si el otro extremo cerró la conexión. */
    public static Frame read(DataInputStream in) throws IOException {
        int m0 = in.read();
        if (m0 < 0) {
            throw new EOFException("conexion cerrada");
        }
        int m1 = in.readUnsignedByte();
        int ver = in.readUnsignedByte();
        if (m0 != MAGIC0 || m1 != MAGIC1) {
            throw new IOException("Trama invalida (magic)");
        }
        if (ver != VERSION) {
            throw new IOException("Version de protocolo no soportada: " + ver);
        }
        int type = in.readUnsignedByte();
        int id = in.readInt();
        int len = in.readInt();
        if (len < 0 || len > MAX_PAYLOAD) {
            throw new IOException("Longitud de payload invalida: " + len);
        }
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Frame(type, id, payload);
    }

    @Override
    public String toString() {
        return Msg.name(type) + "#" + requestId + "(" + payload.length + " B)";
    }
}
