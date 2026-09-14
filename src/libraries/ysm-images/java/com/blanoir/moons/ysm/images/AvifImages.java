package com.blanoir.moons.ysm.images;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/** Official libavif decoder isolated from the game JVM; all model files are temporary. */
final class AvifImages {
    private static final String SHA256 =
            "a03664ddffb9d847eb484af6ceeffce96e03d641da5c577d26662487dee689f2";
    private static volatile Path executable;
    private static final java.util.Map<byte[], byte[]> CACHE = new java.util.WeakHashMap<>();
    private static final int CACHE_LIMIT = 32 * 1024 * 1024;

    static BufferedImage decode(byte[] data) throws IOException {
        byte[] cached;
        synchronized (CACHE) {
            cached = CACHE.get(data);
        }
        if (cached != null) return ImageIO.read(new java.io.ByteArrayInputStream(cached));
        Path decoder = executable();
        Path directory = Files.createTempDirectory("moons-ysm-avif-image-");
        Path input = directory.resolve("input.avif"),
                output = directory.resolve("output.png"),
                log = directory.resolve("decode.log");
        Process process = null;
        try {
            Files.write(input, data);
            process =
                    new ProcessBuilder(
                                    decoder.toString(),
                                    "--jobs",
                                    "2",
                                    "--depth",
                                    "8",
                                    "--png-compress",
                                    "0",
                                    "--size-limit",
                                    "67108864",
                                    "--dimension-limit",
                                    "8192",
                                    "--",
                                    input.toString(),
                                    output.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
            if (!process.waitFor(20, TimeUnit.SECONDS))
                throw new IOException("AVIF decoding exceeded 20 seconds");
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                String message = Files.readString(log);
                throw new IOException(
                        "AVIF decode failed: "
                                + message.substring(0, Math.min(1200, message.length())).strip());
            }
            byte[] png = Files.readAllBytes(output);
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (image == null) throw new IOException("AVIF decoder produced no image");
            if (png.length <= CACHE_LIMIT) {
                synchronized (CACHE) {
                    int bytes = CACHE.values().stream().mapToInt(value -> value.length).sum();
                    if (bytes + png.length > CACHE_LIMIT) CACHE.clear();
                    CACHE.put(data, png);
                }
            }
            return image;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("AVIF decoding interrupted", interrupted);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            // Fixed filenames in this invocation's own temporary directory only.
            for (Path file : new Path[] {input, output, log, directory}) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanup) {
                    file.toFile().deleteOnExit();
                }
            }
        }
    }

    private static synchronized Path executable() throws IOException {
        if (executable != null && Files.isRegularFile(executable)) return executable;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("windows") || !(arch.equals("amd64") || arch.equals("x86_64")))
            throw new IOException("This AVIF tool bundle supports Windows x64");
        byte[] bytes;
        try (var input = AvifImages.class.getResourceAsStream("/avif/windows-x64/avifdec.exe")) {
            if (input == null)
                throw new IOException("AVIF decoder is missing from moons-ysm-images.jar");
            bytes = input.readAllBytes();
        }
        if (!digest(bytes).equals(SHA256)) throw new IOException("AVIF decoder checksum mismatch");
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "moons-ysm-avif", SHA256);
        Files.createDirectories(cache);
        Path target = cache.resolve("avifdec.exe");
        if (!Files.isRegularFile(target) || !digest(Files.readAllBytes(target)).equals(SHA256)) {
            Path temp = Files.createTempFile(cache, "decoder-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(
                            temp,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        executable = target;
        return target;
    }

    private static String digest(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }
}
