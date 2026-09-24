package com.blanoir.moons.client.module.impl.world.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GeodeBlockSettings;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runs Minecraft's actual generator, then strips all material identities before detection. */
final class VanillaGeodeVerification {
    static void run() {
        var config = config();
        int[] detected = new int[4];
        int samples = 96;
        double worstCenterError = 0;
        for (int sample = 0; sample < samples; sample++) {
            long seed = sample < 32 ? sample : 100_003L + sample * 7919L;
            Map<BlockPos, BlockState> blocks = generate(config, seed);
            var air =
                    blocks.entrySet().stream()
                            .filter(e -> e.getValue().isAir())
                            .map(Map.Entry::getKey)
                            .toList();
            double centerX = air.stream().mapToInt(BlockPos::getX).average().orElseThrow();
            double centerY = air.stream().mapToInt(BlockPos::getY).average().orElseThrow();
            double centerZ = air.stream().mapToInt(BlockPos::getZ).average().orElseThrow();
            for (int mode = 0; mode < detected.length; mode++) {
                var found = CavityEvidence.locate(snapshots(blocks, mode, centerX), List.of());
                if (!found.isEmpty()) detected[mode]++;
                if (mode == 0 && !found.isEmpty()) {
                    double error =
                            found.getFirst()
                                    .center()
                                    .distanceTo(
                                            new net.minecraft.world.phys.Vec3(
                                                    centerX + .5, centerY + .5, centerZ + .5));
                    worstCenterError = Math.max(worstCenterError, error);
                }
                if (found.size() > 1)
                    throw new AssertionError("Duplicate geode " + seed + " mode " + mode);
            }
        }
        System.out.println(
                "Vanilla generated geodes: normal/stone/cut/stone-cut="
                        + java.util.Arrays.toString(detected)
                        + "/"
                        + samples
                        + "; largest center error="
                        + worstCenterError);
        // Shape-only detection now favors precision: keep the real cave negatives rejected,
        // even if some noisy or heavily cut geodes cannot be distinguished reliably.
        // These fixture floors are not estimates of live-server accuracy.
        for (int mode = 0; mode < detected.length; mode++) {
            double minimum = mode < 2 ? .60 : .50;
            if (detected[mode] < samples * minimum)
                throw new AssertionError(
                        "Vanilla contour regression in mode " + mode + ": " + detected[mode]);
        }
        if (worstCenterError > 3.25)
            throw new AssertionError("Geode centers drifted into adjacent caves");
    }

    private static Object config() {
        boolean modern = Feature.class.isInterface();
        var stream =
                Feature.class.getResourceAsStream(
                        modern
                                ? "/data/minecraft/worldgen/feature/amethyst_geode.json"
                                : "/data/minecraft/worldgen/configured_feature/amethyst_geode.json");
        if (stream == null) throw new AssertionError("Missing Minecraft geode configuration");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            var json = modern ? root : root.getAsJsonObject("config");
            // Newer versions use registry-backed block sets. In this all-stone fixture there
            // are no protected/invalid blocks; express those two guards as empty direct sets.
            // Keep every geometric, noise, crack and crystal parameter from the bundled data.
            if (GeodeBlockSettings.class.getDeclaredField("cannotReplace").getType()
                    != TagKey.class) {
                json.getAsJsonObject("blocks").add("cannot_replace", new JsonArray());
                json.getAsJsonObject("blocks").add("invalid_blocks", new JsonArray());
            }
            Class<?> type =
                    Class.forName(
                            modern
                                    ? "net.minecraft.world.level.levelgen.feature.GeodeFeature"
                                    : "net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration");
            Object raw = type.getField("CODEC").get(null);
            Codec<?> codec = raw instanceof MapCodec<?> map ? map.codec() : (Codec<?>) raw;
            return codec.parse(JsonOps.INSTANCE, json).getOrThrow();
        } catch (Exception failure) {
            throw new AssertionError("Could not decode Minecraft geode configuration", failure);
        }
    }

    private static Map<BlockPos, BlockState> generate(Object config, long seed) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        WorldGenLevel level =
                (WorldGenLevel)
                        Proxy.newProxyInstance(
                                WorldGenLevel.class.getClassLoader(),
                                new Class<?>[] {WorldGenLevel.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getSeed" -> seed * 104729 + 71;
                                            case "getBlockState" ->
                                                    blocks.getOrDefault(
                                                            (BlockPos) args[0],
                                                            Blocks.STONE.defaultBlockState());
                                            case "getFluidState" ->
                                                    blocks.getOrDefault(
                                                                    (BlockPos) args[0],
                                                                    Blocks.STONE
                                                                            .defaultBlockState())
                                                            .getFluidState();
                                            case "setBlock" -> {
                                                blocks.put(
                                                        ((BlockPos) args[0]).immutable(),
                                                        (BlockState) args[1]);
                                                yield true;
                                            }
                                            case "ensureCanWrite" -> true;
                                            case "scheduleTick" -> null;
                                            default ->
                                                    throw new AssertionError(
                                                            "Unexpected generator world call: "
                                                                    + method);
                                        });
        boolean placed;
        try {
            var random = RandomSource.create(seed);
            var origin = new BlockPos(-20 + (int) (seed & 1), -30, -20);
            if (Feature.class.isInterface()) {
                placed =
                        (boolean)
                                config.getClass()
                                        .getMethod(
                                                "place",
                                                WorldGenLevel.class,
                                                net.minecraft.world.level.chunk.ChunkGenerator
                                                        .class,
                                                RandomSource.class,
                                                BlockPos.class)
                                        .invoke(config, level, null, random, origin);
            } else {
                Class<?> context =
                        Class.forName(
                                "net.minecraft.world.level.levelgen.feature.FeaturePlaceContext");
                Object value =
                        context.getConstructors()[0].newInstance(
                                Optional.empty(), level, null, random, origin, config);
                Object feature = Feature.class.getField("GEODE").get(null);
                placed = (boolean) Feature.class.getMethod("place", context).invoke(feature, value);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not invoke this version's vanilla generator", failure);
        }
        if (!placed) throw new AssertionError("Geode placement failed in solid fixture");
        return blocks;
    }

    private static List<CavitySnapshot> snapshots(
            Map<BlockPos, BlockState> blocks, int mode, double centerX) {
        var result = new ArrayList<CavitySnapshot>();
        for (int cx = -3; cx <= 0; cx++)
            for (int cz = -3; cz <= 0; cz++) {
                var builder = new CavitySnapshot.Builder(cx, cz);
                for (int y = CavitySnapshot.MIN_Y; y < CavitySnapshot.MAX_Y; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            var state =
                                    blocks.getOrDefault(
                                            new BlockPos(cx * 16 + x, y, cz * 16 + z),
                                            Blocks.STONE.defaultBlockState());
                            builder.set(
                                    x,
                                    y,
                                    z,
                                    state.isAir() || mode >= 2 && cx * 16 + x >= centerX + 1
                                            ? CavitySnapshot.AIR
                                            : state.isSolidRender() || (mode & 1) == 1
                                                    ? CavitySnapshot.WALL
                                                    : state.getFluidState().isEmpty()
                                                            ? CavitySnapshot.DECORATION
                                                            : CavitySnapshot.OTHER);
                        }
                result.add(builder.build());
            }
        return result;
    }
}
