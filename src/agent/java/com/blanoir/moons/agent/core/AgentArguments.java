package com.blanoir.moons.agent.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

final class AgentArguments {
    private AgentArguments() { }

    static Path home(String arguments, Path outerJar) {
        String configuredHome = System.getProperty("moons.home", "").trim();
        if (!configuredHome.isEmpty()) {
            return Path.of(configuredHome).toAbsolutePath().normalize();
        }
        String encoded = value(arguments, "home64");
        if (encoded == null || encoded.isBlank()) {
            return defaultHome();
        }
        String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        return Path.of(decoded).toAbsolutePath().normalize();
    }

    private static Path defaultHome() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve(".moons").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."))
                .resolve(".moons")
                .toAbsolutePath()
                .normalize();
    }

    private static String value(String arguments, String name) {
        if (arguments == null || arguments.isBlank()) return null;
        for (String part : arguments.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0 && part.substring(0, separator).equals(name)) {
                return part.substring(separator + 1);
            }
        }
        return null;
    }
}
