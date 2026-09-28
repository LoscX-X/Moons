package com.blanoir.moons.client.module.impl.world.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.util.EnumSet;

final class StructureChunkScannerVerification {
    static void run() {
        verifyDungeonHeadroom();
        var types =
                EnumSet.of(StructureEvidence.Kind.SPAWNER, StructureEvidence.Kind.AMETHYST_GEODE);
        var section =
                new LevelChunkSection(
                        new PalettedContainer<>(
                                Blocks.STONE.defaultBlockState(),
                                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
                        null);
        section.setBlockState(1, 3, 5, Blocks.SPAWNER.defaultBlockState());
        section.setBlockState(7, 9, 11, Blocks.AIR.defaultBlockState());
        section.setBlockState(3, 5, 7, Blocks.CAVE_VINES.defaultBlockState());
        var copies =
                StructureChunkScanner.capture(new LevelChunkSection[] {section}, -32, types, true);
        section.setBlockState(1, 3, 5, Blocks.STONE.defaultBlockState());
        section.setBlockState(7, 9, 11, Blocks.STONE.defaultBlockState());
        var result = StructureChunkScanner.scan(-2, -3, copies, types, true, () -> false);
        require(
                result.markers().size() == 1
                        && result.markers().getFirst().pos().equals(new BlockPos(-31, -29, -43)),
                "Worker receives an independent snapshot, including odd coordinates and negative chunks");
        require(
                result.cavity().atBlock(-25, -23, -37) == CavitySnapshot.AIR,
                "Odd-coordinate empty cell is retained after source changes");
        require(
                result.cavity().atBlock(-29, -27, -41) == CavitySnapshot.OTHER,
                "Cave plants must not masquerade as an empty geode");
        require(
                result.cavity().atBlock(-32, -32, -48) == CavitySnapshot.WALL,
                "Stone remains solid independently of render visibility");
        require(
                result.cavity().atBlock(-32, -48, -48) == CavitySnapshot.UNKNOWN,
                "Absent section remains unknown");
        require(
                StructureChunkScanner.scan(-2, -3, copies, types, true, () -> true) == null,
                "Cancelled world scans do not publish results");
        var refreshed =
                StructureChunkScanner.scan(
                        -2,
                        -3,
                        StructureChunkScanner.capture(
                                new LevelChunkSection[] {section}, -32, types, true),
                        types,
                        true,
                        () -> false);
        require(
                refreshed.markers().isEmpty(),
                "Removing a spawner is reflected by the next snapshot");
        var solid =
                new LevelChunkSection(
                        new PalettedContainer<>(
                                Blocks.DEEPSLATE.defaultBlockState(),
                                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
                        null);
        var solidCopies =
                StructureChunkScanner.capture(new LevelChunkSection[] {solid}, -16, types, true);
        require(solidCopies.getFirst().blocks() == null, "All-solid palette skips block copying");
        var filled =
                StructureChunkScanner.scan(0, 0, solidCopies, types, true, () -> false).cavity();
        require(
                filled.atBlock(15, -1, 15) == CavitySnapshot.WALL,
                "Solid fill includes section endpoints");
        require(
                StructureChunkScanner.capture(new LevelChunkSection[] {solid}, -16, types, false)
                        .isEmpty(),
                "Unrequested solid section is skipped entirely");
        System.out.println(
                "Structure snapshot isolation, odd voxels, cancellation and palette checks passed.");
    }

    private static void verifyDungeonHeadroom() {
        var types = EnumSet.of(StructureEvidence.Kind.DUNGEON);
        var floor = section(Blocks.STONE);
        var room = section(Blocks.AIR);
        for (int x = 2; x <= 8; x++)
            for (int z = 2; z <= 8; z++)
                floor.setBlockState(x, 15, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        for (int y = 0; y < 3; y++)
            for (int z = 2; z <= 8; z++)
                room.setBlockState(2, y, z, Blocks.COBBLESTONE.defaultBlockState());
        room.setBlockState(5, 0, 5, Blocks.CHEST.defaultBlockState());
        var sections = new LevelChunkSection[] {floor, room};
        var copy = StructureChunkScanner.capture(sections, -32, types, false);
        for (int y = 0; y < 3; y++)
            for (int x = 3; x <= 8; x++)
                for (int z = 2; z <= 8; z++)
                    room.setBlockState(x, y, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        var original = StructureChunkScanner.scan(0, 0, copy, types, false, () -> false);
        require(
                StructureEvidence.locate(original.markers(), types).size() == 1,
                "Dense moss floor with a wall and real headroom survives across a section boundary");
        require(
                original.markers().stream()
                        .anyMatch(m -> m.pos().equals(new BlockPos(5, -17, 5)) && m.openAbove()),
                "A chest on the floor does not erase otherwise valid room evidence");
        var filled =
                StructureChunkScanner.scan(
                        0,
                        0,
                        StructureChunkScanner.capture(sections, -32, types, false),
                        types,
                        false,
                        () -> false);
        require(
                StructureEvidence.locate(filled.markers(), types).isEmpty(),
                "A dense moss pile beside cobblestone must not be inferred as a dungeon");
        room = section(Blocks.STONE);
        var solid =
                StructureChunkScanner.scan(
                        0,
                        0,
                        StructureChunkScanner.capture(
                                new LevelChunkSection[] {floor, room}, -32, types, false),
                        types,
                        false,
                        () -> false);
        require(
                solid.markers().stream().noneMatch(StructureEvidence.Marker::openAbove),
                "Unmarked stone above the floor is not empty space");
        var missing =
                StructureChunkScanner.scan(
                        0,
                        0,
                        StructureChunkScanner.capture(
                                new LevelChunkSection[] {floor}, -32, types, false),
                        types,
                        false,
                        () -> false);
        require(
                missing.markers().stream().noneMatch(StructureEvidence.Marker::openAbove),
                "Missing upper section is not treated as air");
        var air =
                StructureChunkScanner.scan(
                        0,
                        0,
                        StructureChunkScanner.capture(
                                new LevelChunkSection[] {floor, section(Blocks.AIR)},
                                -32,
                                types,
                                false),
                        types,
                        false,
                        () -> false);
        require(
                air.markers().stream().allMatch(StructureEvidence.Marker::openAbove),
                "Marker-free upper section is copied even with geode shape scanning disabled");
    }

    private static LevelChunkSection section(Block block) {
        return new LevelChunkSection(
                new PalettedContainer<>(
                        block.defaultBlockState(),
                        Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
                null);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
