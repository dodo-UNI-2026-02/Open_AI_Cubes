package cubes.tools;

import cubes.cnn.ImagePrep;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Construye el dataset binario (formato CIFAR) a partir de carpetas de imágenes:
 *
 *   origen/airplane/*.jpg, origen/cat/*.jpg, ...
 *
 * Uso: java cubes.tools.DatasetBuilder <carpetaOrigen> <salida.bin> carpeta1=nombre1 carpeta2=nombre2 ...
 * Ejemplo: ... train data/train.bin airplane=avion automobile=automovil cat=gato ship=barco
 *
 * Escribe además classes.txt junto al .bin. Las imágenes se mezclan con semilla fija.
 * Permite agregar clases propias (por ejemplo fotos de pandas) sin otra herramienta.
 */
public final class DatasetBuilder {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Uso: DatasetBuilder <carpetaOrigen> <salida.bin> carpeta=nombre ...");
            System.exit(1);
        }
        File src = new File(args[0]);
        File out = new File(args[1]);
        List<String> names = new ArrayList<String>();
        List<int[]> order = new ArrayList<int[]>();
        List<File[]> files = new ArrayList<File[]>();
        for (int k = 2; k < args.length; k++) {
            String[] kv = args[k].split("=", 2);
            File dir = new File(src, kv[0]);
            File[] imgs = dir.listFiles();
            if (imgs == null) {
                throw new IOException("No existe la carpeta " + dir);
            }
            Arrays.sort(imgs);
            files.add(imgs);
            names.add(kv.length > 1 ? kv[1] : kv[0]);
            for (int i = 0; i < imgs.length; i++) {
                order.add(new int[] {k - 2, i});
            }
        }
        Collections.shuffle(order, new Random(7));

        File parent = out.getAbsoluteFile().getParentFile();
        parent.mkdirs();
        OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
        int count = 0;
        try {
            for (int[] item : order) {
                File f = files.get(item[0])[item[1]];
                byte[] px;
                try {
                    px = ImagePrep.fromFile(f);
                } catch (IOException e) {
                    System.err.println("Se omite " + f + ": " + e.getMessage());
                    continue;
                }
                os.write(item[0]);
                os.write(px);
                count++;
            }
        } finally {
            os.close();
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(parent, "classes.txt")), StandardCharsets.UTF_8);
        try {
            for (String n : names) {
                w.write(n + "\n");
            }
        } finally {
            w.close();
        }
        System.out.println("Escritas " + count + " imágenes en " + out + " con clases " + names);
    }
}
