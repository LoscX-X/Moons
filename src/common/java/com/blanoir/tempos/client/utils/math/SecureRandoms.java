package com.blanoir.tempos.client.utils.math;

import java.security.SecureRandom;
import java.util.UUID;

/** Cryptographic bytes and random UUIDs. */
public final class SecureRandoms {
    private SecureRandoms() {}

    /** Credentials must use the cryptographic source rather than gameplay sampling. */
    public static byte[] secureBytes(int length) {
        byte[] bytes = new byte[length];
        SecureSource.RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static UUID uuid() {
        return UUID.randomUUID();
    }

    private static final class SecureSource {
        private static final SecureRandom RANDOM = new SecureRandom();
    }
}
