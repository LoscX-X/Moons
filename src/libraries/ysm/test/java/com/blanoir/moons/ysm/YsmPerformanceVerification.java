package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel.*;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Repeatable CPU/allocation benchmark; timings are reported, never used as CI thresholds. */
public final class YsmPerformanceVerification {
    private static volatile LocalYsmModel.Mesh sink;

    static void verify(Path directory) throws Exception {
        verifyMaterialPartition();
        RawYsmModel raw = fixture();
        raw.mainEntity.mainModel.bones.get(1).name = "ysmGlowAccent";
        try (var model = new LocalYsmModel(raw)) {
            model.prepareTexture("default");
            var first = model.frame(0, Map.of(), "");
            float[] retained = first.vertices().clone();
            var locator = new org.joml.Matrix4f(first.locators().get("leftArm"));
            var passes = first.passes().stream().map(p -> p.vertices().clone()).toList();
            model.poseOverrides(
                    Map.of("leftArm", new YsmPose(16, 2, 3, 15, 30, 45, 2, 1, 1, false)));
            var second = model.frame(.1, Map.of(), "");
            require(!Arrays.equals(retained, second.vertices()), "pose changes the mesh");
            require(Arrays.equals(first.vertices(), retained), "queued vertices remain unchanged");
            require(
                    first.locators().get("leftArm").equals(locator),
                    "queued locators remain unchanged");
            int offset = 0;
            for (int p = 0; p < first.passes().size(); p++) {
                var pass = first.passes().get(p);
                require(
                        Arrays.equals(pass.vertices(), passes.get(p)),
                        "queued material pass remains unchanged");
                for (float value : pass.vertices())
                    require(value == retained[offset++], "pass concatenation");
            }
            require(
                    offset == retained.length && first.passes().size() == 2,
                    "mixed emissive materials");
            var left = model.frame(.2, Map.of(), "", 1);
            var right = model.frame(.2, Map.of(), "", 2);
            require(
                    left.vertexCount() == 2048 && right.vertexCount() == 2048,
                    "independent arm masks");
            require(
                    Arrays.equals(model.frame(.2, Map.of(), "").vertices(), second.vertices()),
                    "cached geometry survives switching between arm masks and the full body");
            model.poseOverrides(Map.of("leftArm", new YsmPose(0, 0, 0, 0, 0, 0, 0, 1, 1, false)));
            require(
                    model.frame(.3, Map.of(), "").vertexCount() == 2048,
                    "zero scale hides descendants");
            model.poseOverrides(Map.of());
            require(
                    model.frame(.4, Map.of(), "").vertexCount() == 4096,
                    "scratch visibility resets");
        }
        try (var visible = new LocalYsmModel(raw);
                var hidden = new LocalYsmModel(raw)) {
            var poses = Map.of("leftArm", new YsmPose(16, 4, 8, 30, 45, 10, 2, 1, 1, false));
            visible.poseOverrides(poses);
            hidden.poseOverrides(poses);
            for (int i = 0; i < 5; i++) {
                visible.frame(i / 20d, Map.of(), "");
                hidden.updatePose(i / 20d, Map.of(), "");
                for (String bone : visible.runtime().bones().keySet())
                    require(
                            visible.runtime()
                                    .bone(bone)
                                    .absolutePivot
                                    .equals(hidden.runtime().bone(bone).absolutePivot),
                            "pose-only absolute pivot: " + bone);
            }
        }
        RawTexture transparent = new RawTexture();
        transparent.name = "transparent";
        transparent.imageFormat = -1;
        transparent.width = transparent.height = 1;
        transparent.data = new byte[4];
        raw.mainEntity.textures.put(transparent.name, transparent);
        raw.properties.defaultTexture = "default";
        Path preferences = directory.resolve("performance-parameters.json");
        Files.writeString(
                preferences.resolveSibling(preferences.getFileName() + ".texture.json"),
                "\"transparent\"");
        // A prepared session crosses a real worker boundary before the render thread uses it.
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try (var session =
                    worker.submit(() -> new YsmSession(new LocalYsmModel(raw), preferences, "", ""))
                            .get()) {
                require(
                        session.texture().equals("transparent"),
                        "saved texture selected during loading");
                require(session.frame(0, Map.of()).vertexCount() == 0, "saved texture body alpha");
                require(
                        session.firstPerson().frame(0, Map.of(), "", 1).vertexCount() == 0,
                        "saved texture arm alpha");
                var pixels =
                        javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(session.texturePng()));
                require(
                        (pixels.getRGB(0, 0) >>> 24) == 0,
                        "uploaded texture matches alpha analysis");
                pixels.flush();
                session.texture("default");
                session.updatePose(.1, Map.of());
                require(
                        session.frame(.1, Map.of()).vertexCount() == 4096,
                        "texture switch restores geometry");
                session.playback(1, true);
                double time = session.time();
                session.updatePose(.2, Map.of());
                require(session.time() == time, "pose-only playback respects pause");
                session.seek(.15);
                for (int i = 0; i < 10 && session.seeking(); i++) session.updatePose(.2, Map.of());
                require(
                        !session.seeking() && session.time() == .15,
                        "pose-only timeline seek completes");
                session.resetWorld();
                require(session.time() == 0, "pose-only world reset");
            }
        }
        System.out.println(
                "YSM_RENDER_LIFECYCLE_VERIFIED snapshots=stable arms=masked materials=mixed pivots=equivalent preparation=worker texture=saved+switch playback=pause+seek+reset");
    }

    private static void verifyMaterialPartition() throws Exception {
        RawYsmModel raw = new RawYsmModel();
        raw.mainEntity.mainModel = new RawGeometry();
        RawBone bone = new RawBone();
        bone.name = "root";
        raw.mainEntity.mainModel.bones.add(bone);
        RawCube cube = new RawCube();
        bone.cubes.add(cube);
        // Opaque, partial alpha, invisible, and an opaque UV region beside partial-alpha texels.
        for (int x : new int[] {2, 17, 26, 13}) {
            RawFace face = new RawFace();
            face.positions = new float[][] {{x, 0, 0}, {x + 1, 0, 0}, {x + 1, 1, 0}, {x, 1, 0}};
            face.normal = new float[] {0, 0, 1};
            face.u = new float[] {x / 32f, (x + 3) / 32f, (x + 3) / 32f, x / 32f};
            face.v = new float[] {.25f, .25f, .5f, .5f};
            cube.faces.add(face);
        }
        RawTexture texture = new RawTexture();
        texture.name = "mixed";
        texture.imageFormat = -1;
        texture.width = 32;
        texture.height = 16;
        texture.data = new byte[32 * 16 * 4];
        Arrays.fill(texture.data, (byte) 255);
        for (int y = 0; y < 16; y++)
            for (int x = 16; x < 32; x++)
                texture.data[(y * 32 + x) * 4 + 3] = (byte) (x < 24 ? 128 : 0);
        raw.mainEntity.textures.put(texture.name, texture);
        RawTexture solid = new RawTexture();
        solid.name = "solid";
        solid.imageFormat = -1;
        solid.width = 32;
        solid.height = 16;
        solid.data = new byte[texture.data.length];
        Arrays.fill(solid.data, (byte) 255);
        raw.mainEntity.textures.put(solid.name, solid);
        try (var model = new LocalYsmModel(raw)) {
            model.prepareTexture("mixed");
            var mixed = model.frame(0, Map.of(), "");
            float[] retained = mixed.vertices().clone();
            require(mixed.vertexCount() == 12, "only invisible faces are omitted");
            require(
                    mixed.passes().stream()
                                    .filter(p -> !p.translucent())
                                    .mapToInt(p -> p.vertices().length / 8)
                                    .sum()
                            == 8,
                    "fully opaque faces bypass blending");
            require(
                    mixed.passes().stream()
                                    .filter(LocalYsmModel.Pass::translucent)
                                    .mapToInt(p -> p.vertices().length / 8)
                                    .sum()
                            == 4,
                    "nearest sampling keeps adjacent opaque UV regions out of blending");
            require(
                    Arrays.equals(retained, model.frame(.1, Map.of(), "").vertices()),
                    "unchanged bones retain identical geometry");
            model.poseOverrides(Map.of("root", new YsmPose(0, 0, 0, 0, 0, 0, 1, 1, 1, true)));
            require(
                    model.frame(.2, Map.of(), "").vertexCount() == 0,
                    "hidden cached geometry is omitted");
            model.poseOverrides(Map.of());
            require(
                    Arrays.equals(retained, model.frame(.3, Map.of(), "").vertices()),
                    "restored bones rebuild discarded cache slices");
            model.prepareTexture("solid");
            var restored = model.frame(.4, Map.of(), "");
            require(
                    restored.vertexCount() == 16
                            && restored.passes().stream()
                                    .noneMatch(LocalYsmModel.Pass::translucent),
                    "texture changes rebuild visibility and materials");
            require(
                    Arrays.equals(retained, mixed.vertices()),
                    "texture rebuild leaves queued geometry intact");
            model.prepareTexture("mixed");
            require(
                    Arrays.equals(retained, model.frame(.5, Map.of(), "").vertices()),
                    "switching back restores material partitions");
        }
        bone.name = "ysmGlowRoot";
        try (var model = new LocalYsmModel(raw)) {
            model.prepareTexture("mixed");
            var glow = model.frame(0, Map.of(), "");
            require(
                    glow.passes().stream().allMatch(LocalYsmModel.Pass::glow),
                    "emissive material is preserved");
            require(
                    glow.passes().stream()
                                    .filter(p -> !p.translucent())
                                    .mapToInt(p -> p.vertices().length / 8)
                                    .sum()
                            == 8,
                    "fully opaque emissive faces bypass blending");
            require(
                    glow.passes().stream()
                                    .filter(LocalYsmModel.Pass::translucent)
                                    .mapToInt(p -> p.vertices().length / 8)
                                    .sum()
                            == 4,
                    "partial-alpha emissive faces retain blending");
        }
        System.out.println(
                "YSM_MATERIAL_PARTITION_VERIFIED opaque+emissive=separate alpha=preserved sampling=nearest cache=visibility+texture+snapshot");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        try (var model = new LocalYsmModel(fixture())) {
            model.prepareTexture("default");
            for (int i = 0; i < 300; i++) sink = model.frame(i / 60d, Map.of(), "");
            var bean = ManagementFactory.getThreadMXBean();
            var allocations =
                    bean instanceof com.sun.management.ThreadMXBean b
                                    && b.isThreadAllocatedMemorySupported()
                            ? b
                            : null;
            if (allocations != null) allocations.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            long before = allocations == null ? 0 : allocations.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int i = 300; i < 1300; i++) sink = model.frame(i / 60d, Map.of(), "");
            long elapsed = System.nanoTime() - start;
            long bytes =
                    allocations == null ? 0 : allocations.getThreadAllocatedBytes(thread) - before;
            System.out.printf(
                    Locale.ROOT,
                    "YSM_MESH_BENCHMARK vertices=%d frames=1000 us/frame=%.1f bytes/frame=%d%n",
                    sink.vertexCount(),
                    elapsed / 1_000_000d,
                    bytes / 1000);
        }
    }

    static RawYsmModel fixture() {
        RawYsmModel raw = new RawYsmModel();
        raw.mainEntity.mainModel = new RawGeometry();
        for (int b = 0; b < 64; b++) {
            RawBone bone = new RawBone();
            bone.name = b == 0 ? "leftArm" : b == 32 ? "rightArm" : "bone" + b;
            bone.parentName = b == 0 || b == 32 ? "" : b < 32 ? "leftArm" : "rightArm";
            raw.mainEntity.mainModel.bones.add(bone);
            for (int c = 0; c < 16; c++) {
                RawCube cube = new RawCube();
                RawFace face = new RawFace();
                face.positions = new float[][] {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
                face.normal = new float[] {0, 0, 1};
                face.u = new float[] {0, 1, 1, 0};
                face.v = new float[] {0, 0, 1, 1};
                cube.faces.add(face);
                bone.cubes.add(cube);
            }
        }
        RawTexture texture = new RawTexture();
        texture.name = "default";
        texture.imageFormat = -1;
        texture.width = texture.height = 1;
        texture.data = new byte[] {-1, -1, -1, -1};
        raw.mainEntity.textures.put(texture.name, texture);
        return raw;
    }
}
