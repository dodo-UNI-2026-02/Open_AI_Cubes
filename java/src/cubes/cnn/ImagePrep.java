package cubes.cnn;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Convierte cualquier imagen (JPG, PNG, BMP, GIF) a 32x32 en formato CIFAR
 * (planos R, G, B de 1024 bytes). Solo usa javax.imageio y java.awt del JDK.
 */
public final class ImagePrep {

    private ImagePrep() {
    }

    public static byte[] fromFile(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) {
            throw new IOException("Formato de imagen no soportado: " + f);
        }
        return toCifar(img);
    }

    public static byte[] fromEncoded(byte[] encoded) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(encoded));
        if (img == null) {
            throw new IOException("Formato de imagen no soportado");
        }
        return toCifar(img);
    }

    public static byte[] toCifar(BufferedImage img) {
        BufferedImage small = img;
        if (img.getWidth() != 32 || img.getHeight() != 32) {
            small = shrink(squareCrop(img), 32);
        }
        byte[] out = new byte[Params.IN_SIZE];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int rgb = small.getRGB(x, y);
                int i = y * 32 + x;
                out[i] = (byte) ((rgb >> 16) & 0xFF);
                out[1024 + i] = (byte) ((rgb >> 8) & 0xFF);
                out[2048 + i] = (byte) (rgb & 0xFF);
            }
        }
        return out;
    }

    /** Recorta el cuadrado central (las imágenes de CIFAR son cuadradas; así no se deforma la foto). */
    static BufferedImage squareCrop(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (w == h) {
            return img;
        }
        int side = Math.min(w, h);
        return img.getSubimage((w - side) / 2, (h - side) / 2, side, side);
    }

    /**
     * Reduce a side x side a la mitad en cada paso: una sola reducción grande (por ejemplo de
     * 3000 px a 32 px) salta píxeles y deja la imagen con ruido; por pasos se promedia bien.
     */
    public static BufferedImage shrink(BufferedImage img, int side) {
        BufferedImage cur = img;
        int w = img.getWidth(), h = img.getHeight();
        do {
            w = Math.max(side, w / 2);
            h = Math.max(side, h / 2);
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, w, h, java.awt.Color.WHITE, null);
            g.dispose();
            cur = next;
        } while (w > side || h > side);
        return cur;
    }

    /** Reconstruye una imagen visible a partir de los planos CIFAR (para miniaturas). */
    public static BufferedImage toImage(byte[] raw, int off) {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int i = off + y * 32 + x;
                int r = raw[i] & 0xFF, g = raw[i + 1024] & 0xFF, b = raw[i + 2048] & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }
}
