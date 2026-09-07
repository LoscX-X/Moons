package com.blanoir.moons.client.web;

import java.util.regex.Pattern;

/** Reads the in-memory hardware ID supplied by the external helper. */
public final class HardwareId {
    private static final Pattern VALID = Pattern.compile("MOONS(?:-[0-9A-F]{4}){6}");
    private static String cached;

    private HardwareId() {}

    public static synchronized String get() {
        if (cached != null) return cached;
        String value = System.getProperty("moons.hwid", "").trim();
        if (!VALID.matcher(value).matches()) {
            throw new IllegalStateException("External HWID was not supplied for this load.");
        }
        cached = value;
        return value;
    }
}
