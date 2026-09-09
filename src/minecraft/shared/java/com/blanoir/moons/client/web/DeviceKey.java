package com.blanoir.moons.client.web;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.utils.math.RandomMath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** Creates a high-entropy credential scoped to one web server binding. */
final class DeviceKey {
    private static final String KEY_PREFIX = "web.deviceKey.";

    private DeviceKey() {}

    static synchronized String get(String socketUrl) {
        if (socketUrl == null || socketUrl.isBlank()) {
            throw new IllegalStateException("Web server is not bound");
        }
        String settingsKey = KEY_PREFIX + endpointId(socketUrl);
        String stored = Settings.getString(settingsKey, "").trim();
        if (stored.matches("[A-Za-z0-9_-]{43}")) return stored;

        byte[] bytes = RandomMath.secureBytes(32);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Settings.setString(settingsKey, generated);
        return generated;
    }

    private static String endpointId(String socketUrl) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(socketUrl.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
