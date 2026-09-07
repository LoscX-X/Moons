package com.blanoir.moons.client.utils.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Reads embedded bytes without owning image, font, cache, or renderer lifetimes. */
public final class EmbeddedResources {
    private EmbeddedResources() {}

    public static byte[] readRequiredBytes(ClassLoader loader, String path, String missingMessage)
            throws IOException {
        try (InputStream input = loader.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException(missingMessage);
            return input.readAllBytes();
        }
    }

    public static byte[] readMimeBase64(ClassLoader loader, String path, String missingMessage)
            throws IOException {
        String encoded =
                new String(readRequiredBytes(loader, path, missingMessage), StandardCharsets.UTF_8);
        return Base64.getMimeDecoder().decode(encoded);
    }
}
