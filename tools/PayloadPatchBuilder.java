import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Builds the compact 26.2 payload overlay embedded by the universal launcher. */
public final class PayloadPatchBuilder {
    private static final String FEATURES_JAR = "META-INF/moons/modules/moons-core-features.jar";
    private static final String METADATA = "META-INF/moons-patch/";

    private PayloadPatchBuilder() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--verify")) {
            verify(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: PayloadPatchBuilder <26.1.jar> <26.2.jar> <patch.zip>");
        }

        Path basePath = Path.of(args[0]);
        Path targetPath = Path.of(args[1]);
        Path outputPath = Path.of(args[2]);
        Map<String, byte[]> baseOuter = readZip(Files.readAllBytes(basePath));
        Map<String, byte[]> targetOuter = readZip(Files.readAllBytes(targetPath));
        byte[] baseFeatures = require(baseOuter, FEATURES_JAR, basePath);
        byte[] targetFeatures = require(targetOuter, FEATURES_JAR, targetPath);

        Map<String, byte[]> outerChanges = changes(baseOuter, targetOuter, FEATURES_JAR);
        TreeSet<String> outerDeletes = deletions(baseOuter, targetOuter, FEATURES_JAR);
        Map<String, byte[]> baseFeatureEntries = readZip(baseFeatures);
        Map<String, byte[]> targetFeatureEntries = readZip(targetFeatures);
        Map<String, byte[]> featureChanges =
                changes(baseFeatureEntries, targetFeatureEntries, null);
        TreeSet<String> featureDeletes = deletions(baseFeatureEntries, targetFeatureEntries, null);

        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(outputPath))) {
            write(output, METADATA + "outer-deletions.txt", lines(outerDeletes));
            write(output, METADATA + "feature-deletions.txt", lines(featureDeletes));
            for (Map.Entry<String, byte[]> entry : outerChanges.entrySet()) {
                write(output, "outer/" + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, byte[]> entry : featureChanges.entrySet()) {
                write(output, "features/" + entry.getKey(), entry.getValue());
            }
        }

        System.out.printf(
                "MOONS_PAYLOAD_PATCH outerChanged=%d outerDeleted=%d "
                        + "featuresChanged=%d featuresDeleted=%d bytes=%d%n",
                outerChanges.size(),
                outerDeletes.size(),
                featureChanges.size(),
                featureDeletes.size(),
                Files.size(outputPath));
    }

    private static void verify(Path expectedPath, Path actualPath) throws IOException {
        Map<String, byte[]> expected = readZip(Files.readAllBytes(expectedPath));
        Map<String, byte[]> actual = readZip(Files.readAllBytes(actualPath));
        byte[] expectedFeatures = expected.remove(FEATURES_JAR);
        byte[] actualFeatures = actual.remove(FEATURES_JAR);
        assertSame("outer payload", expected, actual);
        if (expectedFeatures == null || actualFeatures == null) {
            throw new IllegalStateException("Feature module missing during verification");
        }
        assertSame("feature module", readZip(expectedFeatures), readZip(actualFeatures));
        System.out.printf(
                "MOONS_PAYLOAD_VERIFIED outerEntries=%d featureEntries=%d%n",
                expected.size(), readZip(expectedFeatures).size());
    }

    private static void assertSame(
            String label, Map<String, byte[]> expected, Map<String, byte[]> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            TreeSet<String> missing = new TreeSet<>(expected.keySet());
            missing.removeAll(actual.keySet());
            TreeSet<String> extra = new TreeSet<>(actual.keySet());
            extra.removeAll(expected.keySet());
            throw new IllegalStateException(
                    label + " entry mismatch; missing=" + missing + ", extra=" + extra);
        }
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            if (!Arrays.equals(entry.getValue(), actual.get(entry.getKey()))) {
                throw new IllegalStateException(label + " content mismatch: " + entry.getKey());
            }
        }
    }

    private static byte[] require(Map<String, byte[]> entries, String name, Path source) {
        byte[] value = entries.get(name);
        if (value == null) {
            throw new IllegalStateException("Missing " + name + " in " + source);
        }
        return value;
    }

    private static Map<String, byte[]> readZip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), readAll(input));
                }
            }
        }
        return entries;
    }

    private static Map<String, byte[]> changes(
            Map<String, byte[]> base, Map<String, byte[]> target, String excluded) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : target.entrySet()) {
            if (entry.getKey().equals(excluded)) {
                continue;
            }
            byte[] previous = base.get(entry.getKey());
            if (previous == null || !Arrays.equals(previous, entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static TreeSet<String> deletions(
            Map<String, byte[]> base, Map<String, byte[]> target, String excluded) {
        TreeSet<String> result = new TreeSet<>();
        for (String name : base.keySet()) {
            if (!name.equals(excluded) && !target.containsKey(name)) {
                result.add(name);
            }
        }
        return result;
    }

    private static byte[] lines(TreeSet<String> values) {
        return String.join("\n", values).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(ZipInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void write(ZipOutputStream output, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}
