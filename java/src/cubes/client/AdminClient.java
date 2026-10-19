package cubes.client;

import cubes.net.Msg;
import cubes.tools.Args;

/**
 * Consola de administración (línea de comandos).
 *
 *   estado:        java -cp out cubes.client.AdminClient --host H estado
 *   prueba cruzada: java -cp out cubes.client.AdminClient --host H crosscheck
 *   entrenar:      java -cp out cubes.client.AdminClient --host H entrenar --images 2048 --epochs 1
 *                  --batch 64 --langs all --max-nodes 0 --label distribuido [--publish]
 */
public final class AdminClient {

    public static void main(String[] a) throws Exception {
        Args args = new Args(a);
        String cmd = "estado";
        for (String s : a) {
            if (!s.startsWith("--") && (s.equals("estado") || s.equals("crosscheck") || s.equals("entrenar"))) {
                cmd = s;
            }
        }
        ServerConnection c = new ServerConnection(args.get("host", "127.0.0.1"), args.getInt("port", Msg.CLIENT_PORT));
        try {
            if (cmd.equals("estado")) {
                System.out.print(c.status());
            } else if (cmd.equals("crosscheck")) {
                System.out.print(c.crossCheck());
            } else {
                String summary = c.train(args.getInt("images", 2048), args.getInt("epochs", 1), args.getInt("batch", 64),
                        args.getFloat("lr", 0.01f), args.get("langs", "all"), args.getInt("max-nodes", 0),
                        args.has("publish"), args.get("label", "distribuido"), System.out::println);
                System.out.print(summary);
            }
        } catch (ServerConnection.ServerError e) {
            System.err.println("Error del servidor: " + e.getMessage());
            System.exit(1);
        } finally {
            c.close();
        }
    }
}
