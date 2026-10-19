package cubes.net;

/** Códigos de mensaje del protocolo OC/1 (ver docs/PROTOCOLO.md). */
public final class Msg {

    private Msg() {
    }

    public static final int CLIENT_PORT = 5000;
    public static final int NODE_PORT = 6000;

    // Nodo <-> servidor
    public static final int REGISTER = 0x01;
    public static final int REGISTER_OK = 0x02;
    public static final int HEARTBEAT = 0x03;
    public static final int BYE = 0x04;
    public static final int SET_WEIGHTS = 0x10;
    public static final int TRAIN_TASK = 0x11;
    public static final int GRADIENTS = 0x12;
    public static final int INFER_TASK = 0x20;
    public static final int INFER_RESULT = 0x21;

    // Cliente <-> servidor
    public static final int LOGIN = 0x40;
    public static final int LOGIN_OK = 0x41;
    public static final int IDENTIFY = 0x42;
    public static final int RESULT = 0x43;
    public static final int HISTORY_REQ = 0x44;
    public static final int HISTORY = 0x45;
    public static final int IDENTIFY_BATCH = 0x46;
    public static final int RESULT_ITEM = 0x47;
    public static final int BATCH_DONE = 0x48;
    public static final int STATUS_REQ = 0x50;
    public static final int STATUS = 0x51;
    public static final int TRAIN_REQ = 0x52;
    public static final int TRAIN_PROGRESS = 0x53;
    public static final int TRAIN_DONE = 0x54;
    public static final int CROSSCHECK_REQ = 0x55;
    public static final int CROSSCHECK = 0x56;

    public static final int ERROR = 0x7F;

    /** Slots de pesos en los nodos. */
    public static final int SLOT_SERVING = 0;
    public static final int SLOT_TRAINING = 1;

    /** Formatos de imagen en IDENTIFY. */
    public static final int FORMAT_RAW = 0;
    public static final int FORMAT_ENCODED = 1;

    public static String name(int type) {
        switch (type) {
            case REGISTER: return "REGISTER";
            case REGISTER_OK: return "REGISTER_OK";
            case HEARTBEAT: return "HEARTBEAT";
            case BYE: return "BYE";
            case SET_WEIGHTS: return "SET_WEIGHTS";
            case TRAIN_TASK: return "TRAIN_TASK";
            case GRADIENTS: return "GRADIENTS";
            case INFER_TASK: return "INFER_TASK";
            case INFER_RESULT: return "INFER_RESULT";
            case LOGIN: return "LOGIN";
            case LOGIN_OK: return "LOGIN_OK";
            case IDENTIFY: return "IDENTIFY";
            case RESULT: return "RESULT";
            case HISTORY_REQ: return "HISTORY_REQ";
            case HISTORY: return "HISTORY";
            case IDENTIFY_BATCH: return "IDENTIFY_BATCH";
            case RESULT_ITEM: return "RESULT_ITEM";
            case BATCH_DONE: return "BATCH_DONE";
            case STATUS_REQ: return "STATUS_REQ";
            case STATUS: return "STATUS";
            case TRAIN_REQ: return "TRAIN_REQ";
            case TRAIN_PROGRESS: return "TRAIN_PROGRESS";
            case TRAIN_DONE: return "TRAIN_DONE";
            case CROSSCHECK_REQ: return "CROSSCHECK_REQ";
            case CROSSCHECK: return "CROSSCHECK";
            case ERROR: return "ERROR";
            default: return "0x" + Integer.toHexString(type);
        }
    }
}
