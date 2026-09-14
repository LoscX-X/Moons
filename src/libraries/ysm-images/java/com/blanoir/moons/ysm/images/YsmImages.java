package com.blanoir.moons.ysm.images;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Explicit decoder entry points; no global ImageIO registration or native library lifecycle. */
public final class YsmImages {
    private YsmImages() {}

    public static BufferedImage decode(byte[] data, int format) throws IOException {
        try {
            BufferedImage image;
            if (format == 4) {
                image = WebpImages.decode(data);
            } else if (format == 5) {
                image = AvifImages.decode(data);
            } else throw new IOException("Unsupported ImageStream format: " + format);
            if (image == null) throw new IOException("Image decoder returned no pixels");
            validate(image.getWidth(), image.getHeight());
            return image;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(
                    "Could not decode " + (format == 4 ? "WebP" : "AVIF") + " texture", error);
        }
    }

    static void validate(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192)
            throw new IOException("Invalid texture dimensions " + width + "x" + height);
    }
}
