package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Reads actual bundled templates, then passes their blocks through the production snapshot scanner. */
final class VanillaStructureMarkerVerification {
    static void run() {
        for (int i = 1; i <= 3; i++) {
            verify(
                    "ancient_city/city_center/city_center_" + i,
                    StructureEvidence.Kind.ANCIENT_CITY);
        }
        for (String template :
                List.of(
                        "reward/vault",
                        "reward/ominous_vault",
                        "spawner/breeze/breeze",
                        "spawner/melee/zombie",
                        "spawner/ranged/skeleton")) {
            verify("trial_chambers/" + template, StructureEvidence.Kind.TRIAL_CHAMBER);
        }
        // A large ordinary underground build used to satisfy the city size rule and trial mapping.
        for (Block material :
                List.of(
                        Blocks.DEEPSLATE_BRICKS,
                        Blocks.POLISHED_DEEPSLATE,
                        Blocks.TUFF_BRICKS,
                        BuiltInRegistries.BLOCK
                                .getOptional(Identifier.parse("minecraft:copper_bulb"))
                                .orElseThrow())) {
            var section = section(material);
            var enabled =
                    EnumSet.of(
                            StructureEvidence.Kind.ANCIENT_CITY,
                            StructureEvidence.Kind.TRIAL_CHAMBER);
            var copies =
                    StructureChunkScanner.capture(
                            new LevelChunkSection[] {section}, -48, enabled, false);
            var result = StructureChunkScanner.scan(-1, 1, copies, enabled, false, () -> false);
            require(
                    result.markers().isEmpty()
                            && StructureEvidence.locate(result.markers(), enabled).isEmpty(),
                    "Ordinary construction marked as a structure: " + material);
        }
        System.out.println(
                "MOONS_STRUCTURE_ANCHORS_VERIFIED vanilla-templates=8 snapshot-scanner ordinary-builds isolated-city-markers");
    }

    private static void verify(String name, StructureEvidence.Kind expected) {
        try (var stream =
                Blocks.class.getResourceAsStream("/data/minecraft/structure/" + name + ".nbt")) {
            require(stream != null, "Missing vanilla structure template: " + name);
            var nbt = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            var palette = nbt.getListOrEmpty("palette");
            var states = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
            for (int i = 0; i < palette.size(); i++) {
                var paletteEntry = palette.getCompoundOrEmpty(i);
                // 26.3 bundled block states use id; earlier templates retain Name.
                var id = paletteEntry.getStringOr("Name", paletteEntry.getStringOr("id", ""));
                states.add(
                        BuiltInRegistries.BLOCK
                                .getOptional(Identifier.parse(id))
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Unknown template block: " + id))
                                .defaultBlockState());
            }
            var size = nbt.getListOrEmpty("size");
            int sx = size.getIntOr(0, 0), sy = size.getIntOr(1, 0), sz = size.getIntOr(2, 0);
            var enabled = EnumSet.of(expected);
            var markers = new ArrayList<StructureEvidence.Marker>();
            var blocks = nbt.getListOrEmpty("blocks");
            for (int cx = 0; cx < (sx + 15) / 16; cx++) {
                for (int cz = 0; cz < (sz + 15) / 16; cz++) {
                    var sections = new LevelChunkSection[(sy + 15) / 16];
                    for (int i = 0; i < sections.length; i++) sections[i] = section(Blocks.AIR);
                    for (int i = 0; i < blocks.size(); i++) {
                        var block = blocks.getCompoundOrEmpty(i);
                        var pos = block.getListOrEmpty("pos");
                        int x = pos.getIntOr(0, -1),
                                y = pos.getIntOr(1, -1),
                                z = pos.getIntOr(2, -1);
                        if (x >> 4 != cx || z >> 4 != cz) continue;
                        sections[y >> 4].setBlockState(
                                x & 15, y & 15, z & 15, states.get(block.getIntOr("state", -1)));
                    }
                    var copy = StructureChunkScanner.capture(sections, -48, enabled, false);
                    markers.addAll(
                            StructureChunkScanner.scan(cx, cz, copy, enabled, false, () -> false)
                                    .markers());
                }
            }
            require(
                    !StructureEvidence.locate(markers, enabled).isEmpty(),
                    "Vanilla structure lost its identifying evidence: "
                            + name
                            + " markers="
                            + markers.size());
        } catch (Exception failure) {
            throw new AssertionError("Vanilla structure fixture failed: " + name, failure);
        }
    }

    private static LevelChunkSection section(Block material) {
        return new LevelChunkSection(
                new PalettedContainer<>(
                        material.defaultBlockState(),
                        Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
                null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
