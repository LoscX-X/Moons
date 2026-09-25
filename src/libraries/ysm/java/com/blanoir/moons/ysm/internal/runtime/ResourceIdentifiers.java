package com.blanoir.moons.ysm.internal.runtime;

/** Minecraft identifier rules for the loader-independent snapshot queries. */
final class ResourceIdentifiers {
    private ResourceIdentifiers() {}

    static String normalize(String value) {
        if (value == null) return null;
        int split = value.indexOf(':');
        String namespace = split <= 0 ? "minecraft" : value.substring(0, split);
        String path = split < 0 ? value : value.substring(split + 1);
        for (int i = 0; i < namespace.length(); i++) if (!valid(namespace.charAt(i))) return null;
        for (int i = 0; i < path.length(); i++)
            if (path.charAt(i) != '/' && !valid(path.charAt(i))) return null;
        return namespace + ":" + path;
    }

    private static boolean valid(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.';
    }
}
