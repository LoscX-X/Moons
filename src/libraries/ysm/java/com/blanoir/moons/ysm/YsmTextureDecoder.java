package com.blanoir.moons.ysm;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** Converts encoded images and YSM's raw RGBA format to PNG without game or native APIs. */
public final class YsmTextureDecoder {
    private static final int MAX_DIMENSION = 8192;

    private YsmTextureDecoder() {}

    public static byte[] toPng(LocalYsmModel.Texture texture) throws IOException {
        BufferedImage image = decode(texture);
        try {
            return toPng(image);
        } finally {
            image.flush();
        }
    }

    /** The caller owns the decoded pixels and must release them after preparation. */
    public static BufferedImage decode(LocalYsmModel.Texture texture) throws IOException {
        byte[] data = texture.data();
        if (data == null || data.length == 0)
            throw new IOException("Empty model texture: " + texture.name());
        BufferedImage image = texture.format() == -1 ? decodeRgba(texture) : decodeEncoded(texture);
        if (image == null) throw new IOException("Cannot decode model texture: " + texture.name());
        try {
            validateDimensions(image.getWidth(), image.getHeight());
            return image;
        } catch (IOException failure) {
            image.flush();
            throw failure;
        }
    }

    static byte[] toPng(BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
        return output.toByteArray();
    }

    private static BufferedImage decodeRgba(LocalYsmModel.Texture texture) throws IOException {
        int width = texture.width();
        int height = texture.height();
        validateDimensions(width, height);
        long expected = (long) width * height * 4;
        byte[] data = texture.data();
        if (data.length < expected) {
            throw new IOException(
                    "Truncated RGBA texture "
                            + texture.name()
                            + ": expected "
                            + expected
                            + " bytes, got "
                            + data.length);
        }
        // -1 explicitly means R,G,B,A bytes in row order, even if they resemble an image header.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++, offset += 4) {
                row[x] =
                        ((data[offset + 3] & 0xff) << 24)
                                | ((data[offset] & 0xff) << 16)
                                | ((data[offset + 1] & 0xff) << 8)
                                | (data[offset + 2] & 0xff);
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    private static BufferedImage decodeEncoded(LocalYsmModel.Texture texture) throws IOException {
        byte[] data = texture.data();
        boolean webp =
                data.length >= 12
                        && data[0] == 'R'
                        && data[1] == 'I'
                        && data[2] == 'F'
                        && data[3] == 'F'
                        && data[8] == 'W'
                        && data[9] == 'E'
                        && data[10] == 'B'
                        && data[11] == 'P';
        boolean avif =
                data.length >= 12
                        && data[4] == 'f'
                        && data[5] == 't'
                        && data[6] == 'y'
                        && data[7] == 'p';
        if (webp || avif) {
            if (texture.width() > 0 && texture.height() > 0)
                validateDimensions(texture.width(), texture.height());
            return com.blanoir.moons.ysm.images.YsmImages.decode(data, webp ? 4 : 5);
        }
        // Inspect dimensions before allocating pixels, and let the image header choose its reader.
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(texture.data()))) {
            if (input == null)
                throw new IOException("Cannot open model texture: " + texture.name());
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException(
                        "Unsupported encoded texture "
                                + texture.name()
                                + " (format "
                                + texture.format()
                                + "; RGBA/PNG/JPEG/BMP/WebP/AVIF supported)");
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException(
                    "Invalid model texture dimensions "
                            + width
                            + "x"
                            + height
                            + " (maximum 8192x8192)");
        }
    }
}
