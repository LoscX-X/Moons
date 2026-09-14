package com.blanoir.moons.ysm.images;

import rip.ysm.imagestream.webp.WebpDecoder;
import rip.ysm.imagestream.webp.data.Frame;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Adds the RIFF ALPH plane omitted by ImageStream's VP8 decoder. */
final class WebpImages {
    static BufferedImage decode(byte[] data) throws Exception {
        WebpDecoder decoder = new WebpDecoder();
        var size = decoder.readDimension(data);
        YsmImages.validate(size.width, size.height);
        BufferedImage color = decoder.read(data);
        byte[] alpha = alphaChunk(data, 12, data.length);
        if (alpha == null || color == null) return color;
        int width = color.getWidth(), height = color.getHeight();
        int flags = alpha[0] & 255;
        int compression = flags & 3, filter = flags >> 2 & 3;
        byte[] values = new byte[width * height];
        if (compression == 0) {
            if (alpha.length != values.length + 1)
                throw new IOException("Invalid WebP alpha plane size");
            System.arraycopy(alpha, 1, values, 0, values.length);
        } else if (compression == 1) {
            // ALPH is a VP8L image stream with implicit dimensions; its green channel holds alpha.
            byte[] lossless = new byte[alpha.length + 4];
            lossless[0] = 0x2f;
            int header = (width - 1) | (height - 1) << 14;
            for (int i = 0; i < 4; i++) lossless[i + 1] = (byte) (header >>> (i * 8));
            System.arraycopy(alpha, 1, lossless, 5, alpha.length - 1);
            BufferedImage plane = Frame.decodeLossless(lossless);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    values[y * width + x] = (byte) (plane.getRGB(x, y) >> 8);
            plane.flush();
        } else throw new IOException("Unsupported WebP alpha compression: " + compression);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int predictor = 0;
                if (filter != 0) {
                    if (y == 0) predictor = x == 0 ? 0 : values[i - 1] & 255;
                    else if (x == 0) predictor = values[i - width] & 255;
                    else if (filter == 1) predictor = values[i - 1] & 255;
                    else if (filter == 2) predictor = values[i - width] & 255;
                    else
                        predictor =
                                Math.clamp(
                                        (values[i - 1] & 255)
                                                + (values[i - width] & 255)
                                                - (values[i - width - 1] & 255),
                                        0,
                                        255);
                }
                values[i] = (byte) ((values[i] & 255) + predictor);
                result.setRGB(x, y, (color.getRGB(x, y) & 0xffffff) | (values[i] & 255) << 24);
            }
        }
        color.flush();
        return result;
    }

    private static byte[] alphaChunk(byte[] data, int start, int end) throws IOException {
        for (int offset = start; offset + 8 <= end; ) {
            String type = new String(data, offset, 4, StandardCharsets.US_ASCII);
            long length = 0;
            for (int i = 0; i < 4; i++) length |= (long) (data[offset + 4 + i] & 255) << (8 * i);
            int payload = offset + 8;
            if (length > end - payload) throw new IOException("Truncated WebP " + type + " chunk");
            if (type.equals("ALPH")) {
                if (length < 1) throw new IOException("Empty WebP alpha chunk");
                return Arrays.copyOfRange(data, payload, payload + (int) length);
            }
            if (type.equals("ANMF")) {
                if (length < 16) throw new IOException("Truncated WebP frame");
                return alphaChunk(data, payload + 16, payload + (int) length);
            }
            if (type.equals("VP8 ") || type.equals("VP8L")) return null;
            offset = payload + (int) length + ((int) length & 1);
        }
        return null;
    }
}
