package com.blanoir.moons.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.jar.JarFile;

/** Minimal entrypoint that makes the loader-neutral API bootstrap-visible first. */
public final class MoonsAgent {
    private static final List<JarFile> BOOTSTRAP_JARS = new ArrayList<>();

    private MoonsAgent() { }

    public static void premain(String arguments, Instrumentation instrumentation) {
        if (writeLunarProbe(arguments)) return;
        start("PREMAIN", arguments, instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        if (writeLunarProbe(arguments)) return;
        start("ATTACH", arguments, instrumentation);
    }

    private static boolean writeLunarProbe(String arguments) {
        String source = argument(arguments, "lunarProbe");
        if (source == null) return false;

        try {
            String encodedPath = argument(arguments, "probe64");
            if (encodedPath == null) {
                throw new IllegalArgumentException("Missing probe64 argument");
            }
            Path output = Path.of(new String(
                    Base64.getUrlDecoder().decode(encodedPath), StandardCharsets.UTF_8
            )).toAbsolutePath().normalize();
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            String command = System.getProperty("sun.java.command", "unknown")
                    .replace('\r', ' ')
                    .replace('\n', ' ');
            String line = "source=" + source
                    + " pid=" + ProcessHandle.current().pid()
                    + " java=" + System.getProperty("java.version", "unknown")
                    + " command=" + command
                    + " time=" + Instant.now();
            Files.writeString(
                    output,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            System.out.println(AgentBranding.prefix() + " Lunar premain probe reached target JVM via " + source);
        } catch (Throwable failure) {
            System.err.println(AgentBranding.prefix() + " Lunar premain probe could not write its result: " + failure);
        }
        return true;
    }

    private static synchronized void start(String mode, String arguments, Instrumentation instrumentation) {
        try {
            configureBranding(arguments);
            Path outerJar = locateOuterJar(arguments);
            Path apiJar = PayloadCache.extract(
                    outerJar,
                    "META-INF/moons/bootstrap/moons-api.jar",
                    "moons-api.jar"
            );
            JarFile bootstrapJar = new JarFile(apiJar.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJar);
            BOOTSTRAP_JARS.add(bootstrapJar);

            Class<?> controller = Class.forName(
                    "com.blanoir.moons.agent.core.AgentController",
                    true,
                    MoonsAgent.class.getClassLoader()
            );
            controller.getMethod(
                    "start",
                    Instrumentation.class,
                    String.class,
                    Path.class,
                    String.class
            ).invoke(null, instrumentation, mode, outerJar, arguments == null ? "" : arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            System.err.println(AgentBranding.prefix() + " Agent startup failed: " + cause);
            cause.printStackTrace(System.err);
        } catch (Throwable failure) {
            System.err.println(AgentBranding.prefix() + " Agent startup failed: " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static Path locateOuterJar(String arguments) throws Exception {
        String encoded = argument(arguments, "jar64");
        if (encoded != null && !encoded.isBlank()) {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return Path.of(decoded).toAbsolutePath().normalize();
        }
        return Path.of(MoonsAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
    }

    private static void configureBranding(String arguments) {
        String encoded = argument(arguments, "name64");
        if (encoded == null || encoded.isBlank()) return;
        AgentBranding.setName(new String(
                Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
    }

    private static String argument(String arguments, String name) {
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
