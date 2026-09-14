package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.resource.ModelResourceContainer;
import com.blanoir.moons.ysm.internal.resource.YSMFolderDeserializer;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

/** Runs without Minecraft or Fabric on its classpath. Uses independently known geometry/animation values. */
public final class YsmCoreVerification {
    private static void verifyEncodedTextures() throws Exception {
        for (String extension : java.util.List.of("webp", "avif", "lossy.webp")) {
            try (var stream =
                    YsmCoreVerification.class.getResourceAsStream(
                            "/textures/"
                                    + (extension.equals("lossy.webp")
                                            ? "blocks-lossy.webp"
                                            : "blocks." + extension))) {
                if (stream == null) throw new AssertionError("Missing texture fixture");
                var texture =
                        new LocalYsmModel.Texture(
                                "fixture",
                                stream.readAllBytes(),
                                extension.endsWith("webp") ? 4 : 5,
                                16,
                                16);
                var pixels =
                        javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(YsmTextureDecoder.toPng(texture)));
                if (pixels.getWidth() != 16 || pixels.getHeight() != 16)
                    throw new AssertionError("Image dimensions: " + extension);
                int left = pixels.getRGB(3, 8), right = pixels.getRGB(12, 8);
                if ((left >>> 24) < 250 || Math.abs((right >>> 24) - 96) > 5)
                    throw new AssertionError(
                            "Texture alpha: "
                                    + extension
                                    + " "
                                    + Integer.toHexString(left)
                                    + " "
                                    + Integer.toHexString(right));
                if ((left >> 16 & 255) < 210 || (right >> 8 & 255) < 160)
                    throw new AssertionError("Texture colors: " + extension);
            }
        }
        for (int filter = 0; filter < 4; filter++) {
            try (var stream =
                    YsmCoreVerification.class.getResourceAsStream(
                            "/textures/alpha-filter-" + filter + ".webp")) {
                var texture = new LocalYsmModel.Texture("alpha", stream.readAllBytes(), 4, 16, 16);
                var pixels =
                        ImageIO.read(new ByteArrayInputStream(YsmTextureDecoder.toPng(texture)));
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++)
                        if ((pixels.getRGB(x, y) >>> 24) != ((x * 13 + y * 7) & 255))
                            throw new AssertionError(
                                    "WebP alpha predictor " + filter + " at " + x + "," + y);
            }
        }
        System.out.println(
                "YSM_IMAGES_VERIFIED webp=lossless+lossy+compressed-alpha+4-filters avif=colors+alpha");
    }

    public static void main(String[] args) throws Exception {
        verifyEncodedTextures();
        YsmMotionVerification.verify();
        verifyTextures();
        YsmCodecVerification.verify();
        Path fixture = Files.createTempDirectory("moons-ysm-verification-");
        try {
            writeFixture(fixture);
            YsmPerformanceVerification.verify(fixture);
            YsmRuntimeVerification.verify(fixture);
            LocalYsmModel model = LocalYsmModel.load(fixture);
            check(model.boneCount() == 2, "Hierarchy contains parent and child");
            check(model.texture("").width() == 16, "Texture metadata");
            check(
                    ImageIO.read(
                                            new ByteArrayInputStream(
                                                    YsmTextureDecoder.toPng(model.texture(""))))
                                    .getWidth()
                            == 16,
                    "Folder texture decoded");
            var first = model.frame(0, Map.of(), "idle");
            check(first.vertexCount() == 24, "Cube has six quads");
            var half = model.frame(0.5, Map.of(), "idle");
            near(
                    minX(half) - minX(first),
                    -0.5f,
                    "Position animation uses Bedrock X convention and /16 units");
            var end = model.frame(1, Map.of(), "idle");
            near(
                    minX(end) - minX(first),
                    -1,
                    "Upstream samples final keyframe at the exact loop boundary");
            var loop = model.frame(1.000001, Map.of(), "idle");
            near(minX(loop), minX(first), "Loop wraps after the final keyframe");
            model.resetAnimation();
            var reset = model.frame(20, Map.of(), "idle");
            near(minX(reset), minX(first), "Reload starts fresh animation state");
            try (var reader = new YSMFolderDeserializer(fixture)) {
                RawYsmModel raw = reader.deserialize();
                // Exercise the binary format against a deterministic encrypted fixture produced by
                // the upstream encoder.
                Path binary = fixture.resolve("fixture.ysm");
                Files.write(binary, FixtureEncoder.encode(raw));
                var decoded = LocalYsmModel.load(binary);
                var binaryFrame = decoded.frame(0, Map.of(), "idle");
                check(
                        Arrays.equals(first.vertices(), binaryFrame.vertices()),
                        "Encrypted YSM and folder produce identical geometry");
                check(
                        ImageIO.read(
                                                new ByteArrayInputStream(
                                                        YsmTextureDecoder.toPng(
                                                                decoded.texture(""))))
                                        .getWidth()
                                == 16,
                        "Encrypted YSM texture decoded");
                var rawTexture = raw.mainEntity.textures.values().iterator().next();
                rawTexture.imageFormat = -1;
                rawTexture.width = 2;
                rawTexture.height = 2;
                rawTexture.data = rgbaPixels();
                Files.write(binary, FixtureEncoder.encode(raw));
                var rgbaModel = LocalYsmModel.load(binary);
                verifyRgbaPixels(
                        ImageIO.read(
                                new ByteArrayInputStream(
                                        YsmTextureDecoder.toPng(rgbaModel.texture("")))));
                byte[] corrupt = Files.readAllBytes(binary);
                corrupt[corrupt.length - 1] ^= 1;
                Files.write(binary, corrupt);
                rejected(() -> LocalYsmModel.load(binary), "Corrupted YSM checksum");
            }
            try (var container = ModelResourceContainer.folder(fixture)) {
                rejected(() -> container.read("../outside.png"), "Model resource path traversal");
            }
            RawYsmModel cyclic = new RawYsmModel();
            cyclic.mainEntity.mainModel = new RawGeometry();
            RawBone a = new RawBone(), b = new RawBone();
            a.name = "a";
            a.parentName = "b";
            b.name = "b";
            b.parentName = "a";
            cyclic.mainEntity.mainModel.bones.addAll(List.of(a, b));
            rejected(() -> new LocalYsmModel(cyclic), "Cyclic bone hierarchy");
            if (args.length > 0) {
                LocalYsmModel real = LocalYsmModel.load(Path.of(args[0]));
                var texture = real.texture("");
                byte[] png = YsmTextureDecoder.toPng(texture);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                check(image != null, "Real model texture decoded");
                if (texture.format() == -1) {
                    check(
                            image.getWidth() == texture.width()
                                    && image.getHeight() == texture.height(),
                            "Real RGBA dimensions");
                    int offset = 0;
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++, offset += 4) {
                            byte[] data = texture.data();
                            int expected =
                                    ((data[offset + 3] & 255) << 24)
                                            | ((data[offset] & 255) << 16)
                                            | ((data[offset + 1] & 255) << 8)
                                            | (data[offset + 2] & 255);
                            check(
                                    image.getRGB(x, y) == expected,
                                    "Real RGBA pixel and alpha at " + x + "," + y);
                        }
                    }
                }
                System.out.println(
                        "YSM_REFERENCE_TEXTURE format="
                                + texture.format()
                                + " dimensions="
                                + image.getWidth()
                                + "x"
                                + image.getHeight()
                                + " pngBytes="
                                + png.length);
                var mesh = real.frame(0, Map.of(), "idle");
                for (float value : mesh.vertices())
                    check(Float.isFinite(value), "Real model finite geometry");
                for (String action : real.animations()) {
                    real.resetAnimation();
                    for (int frame = 0; frame < 5; frame++) {
                        var pose =
                                real.frame(
                                        frame * .05,
                                        Map.of("is_on_ground", true, "head_x_rotation", 15f),
                                        action);
                        for (float value : pose.vertices())
                            check(
                                    Float.isFinite(value),
                                    "Real animation finite geometry: " + action);
                    }
                }
                long expressionErrors =
                        real.runtime().diagnostics().stream()
                                .filter(d -> d.startsWith("Expression "))
                                .count();
                System.out.println(
                        "YSM_REFERENCE_ACTIONS sampled="
                                + real.animations().size()
                                + " expressionErrors="
                                + expressionErrors);
                for (String diagnostic : real.runtime().diagnostics())
                    if (diagnostic.startsWith("Expression "))
                        System.out.println(
                                diagnostic.substring(0, Math.min(240, diagnostic.length())));
                System.out.println(
                        "YSM_REFERENCE_MODEL bones="
                                + real.boneCount()
                                + " vertices="
                                + mesh.vertexCount()
                                + " animations="
                                + real.animations().size());
            }
            System.out.println(
                    "YSM_CORE_VERIFIED parsing=folder+crypto3 textures=png+rgba geometry=hierarchy animation=molang+loop lifecycle=reset");
        } finally {
            try (var files = Files.walk(fixture)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }

    private static byte[] rgbaPixels() {
        return new byte[] {
            (byte) 255,
            0,
            0,
            (byte) 255,
            0,
            (byte) 255,
            0,
            (byte) 128,
            0,
            0,
            (byte) 255,
            0,
            0x12,
            0x34,
            0x56,
            0x7f
        };
    }

    private static void verifyRgbaPixels(BufferedImage image) {
        check(image.getWidth() == 2 && image.getHeight() == 2, "RGBA dimensions");
        check(image.getRGB(0, 0) == 0xffff0000, "RGBA red channel order");
        check(image.getRGB(1, 0) == 0x8000ff00, "RGBA partial alpha");
        check(image.getRGB(0, 1) == 0x000000ff, "RGBA transparent color and row order");
        check(image.getRGB(1, 1) == 0x7f123456, "RGBA last pixel");
    }

    private static void verifyTextures() throws Exception {
        var texture = new LocalYsmModel.Texture("rgba", rgbaPixels(), -1, 2, 2);
        byte[] png = YsmTextureDecoder.toPng(texture);
        verifyRgbaPixels(ImageIO.read(new ByteArrayInputStream(png)));
        verifyRgbaPixels(
                ImageIO.read(
                        new ByteArrayInputStream(
                                YsmTextureDecoder.toPng(
                                        new LocalYsmModel.Texture("png", png, 0, 0, 0)))));
        rejected(
                () ->
                        YsmTextureDecoder.toPng(
                                new LocalYsmModel.Texture("short", new byte[15], -1, 2, 2)),
                "Truncated RGBA");
        rejected(
                () ->
                        YsmTextureDecoder.toPng(
                                new LocalYsmModel.Texture("zero", rgbaPixels(), -1, 0, 2)),
                "Zero RGBA dimension");
        rejected(
                () ->
                        YsmTextureDecoder.toPng(
                                new LocalYsmModel.Texture(
                                        "huge",
                                        rgbaPixels(),
                                        -1,
                                        Integer.MAX_VALUE,
                                        Integer.MAX_VALUE)),
                "Oversized RGBA dimensions");
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        BufferedImage rawHeader =
                ImageIO.read(
                        new ByteArrayInputStream(
                                YsmTextureDecoder.toPng(
                                        new LocalYsmModel.Texture(
                                                "raw-header", signature, -1, 2, 1))));
        check(
                rawHeader.getRGB(0, 0) == 0x4789504e,
                "Raw RGBA must not be mistaken for an encoded header");
    }

    private static void writeFixture(Path dir) throws Exception {
        Files.writeString(
                dir.resolve("ysm.json"),
                """
                {"spec":2,"metadata":{"name":"Moons verification cube"},"files":{"player":{"model":{"main":"main.json"},"texture":["texture.png"],"animation":{"main":"main.animation.json"}}}}
                """);
        Files.writeString(
                dir.resolve("main.json"),
                """
                {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test","texture_width":16,"texture_height":16},"bones":[{"name":"child","parent":"root","pivot":[0,0,0],"cubes":[{"origin":[0,0,0],"size":[16,16,16],"uv":[0,0]}]},{"name":"root","pivot":[0,0,0]}]}]}
                """);
        Files.writeString(
                dir.resolve("main.animation.json"),
                """
                {"format_version":"1.8.0","animations":{"idle":{"loop":true,"animation_length":1,"bones":{"root":{"position":{"0":[0,0,0],"1":[16,0,0]}}}}}}
                """);
        ImageIO.write(
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
                "png",
                dir.resolve("texture.png").toFile());
    }

    private static float minX(LocalYsmModel.Mesh mesh) {
        float min = Float.POSITIVE_INFINITY;
        for (int i = 0; i < mesh.vertices().length; i += 8) min = Math.min(min, mesh.vertices()[i]);
        return min;
    }

    private static void near(float actual, float expected, String message) {
        check(
                Math.abs(actual - expected) < 0.0001f,
                message + " actual=" + actual + " expected=" + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(Checked action, String message) throws Exception {
        try {
            action.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError(message + " was accepted");
    }

    @FunctionalInterface
    private interface Checked {
        Object run() throws Exception;
    }
}
