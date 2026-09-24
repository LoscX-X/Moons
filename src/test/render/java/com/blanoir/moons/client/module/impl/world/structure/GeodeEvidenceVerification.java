package com.blanoir.moons.client.module.impl.world.structure;

import static com.blanoir.moons.client.module.impl.world.structure.StructureEvidence.Kind.AMETHYST_GEODE;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

final class GeodeEvidenceVerification {
    static void run() {
        for (var block : List.of(Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.CALCITE))
            require(StructureEvidence.marker(block) == AMETHYST_GEODE, "Geode material mapping");
        var opened = geode(-16, -25, 15, true, false);
        require(locate(opened).size() == 1, "Irregular open shell crossing chunk boundaries");
        require(
                !locate(opened).getFirst().tentative(),
                "Layered evidence has the regular geode label");
        var duplicate = new ArrayList<>(opened);
        duplicate.addAll(opened);
        Collections.shuffle(duplicate, new Random(14));
        require(locate(duplicate).equals(locate(opened)), "Order and duplicate invariant");
        var pair = new ArrayList<>(opened);
        pair.addAll(geode(10, -25, 15, false, false));
        require(locate(pair).size() == 2, "Nearby separate shells remain separate");
        require(locate(geode(0, 0, 0, false, false)).size() == 1, "Closed shell");
        require(
                locate(geode(0, 0, 0, false, true)).isEmpty(),
                "Solid purple mass is not a hollow geode");
        require(
                locate(opened.stream().filter(m -> m.calcite()).toList()).isEmpty(),
                "Calcite alone does not locate mountain geodes");
        var shapeOnly = locate(opened.stream().filter(m -> !m.calcite()).toList());
        require(
                shapeOnly.size() == 1
                        && shapeOnly.getFirst().tentative()
                        && shapeOnly.getFirst().label().startsWith("Possible"),
                "A substantial purple shell survives removal or masking of calcite as a possible geode");
        require(shapeOnly.getFirst().visit().tentative(), "Visits retain tentative status");
        var misplaced =
                opened.stream()
                        .map(
                                m ->
                                        m.calcite()
                                                ? new StructureEvidence.Marker(
                                                        AMETHYST_GEODE,
                                                        m.pos().offset(60, 0, 0),
                                                        false,
                                                        true)
                                                : m)
                        .toList();
        require(
                locate(misplaced).size() == 1 && locate(misplaced).getFirst().tentative(),
                "Distant calcite cannot upgrade shape evidence to layered evidence");
        var flat = new ArrayList<StructureEvidence.Marker>();
        for (int x = 0; x < 12; x++)
            for (int z = 0; z < 12; z++) {
                flat.add(new StructureEvidence.Marker(AMETHYST_GEODE, new BlockPos(x, 0, z)));
                flat.add(
                        new StructureEvidence.Marker(
                                AMETHYST_GEODE, new BlockPos(x, -1, z), false, true));
            }
        require(locate(flat).isEmpty(), "Two material paving has no three dimensional shell");
        require(
                StructureEvidence.locate(opened, EnumSet.noneOf(StructureEvidence.Kind.class))
                        .isEmpty(),
                "Geode target can be disabled");
        require(locate(List.of()).isEmpty(), "Removed geode clears on a fresh snapshot");
        System.out.println(
                "Geode shell, opening, layer, deduplication and negative checks passed.");
    }

    private static List<StructureEvidence.Marker> geode(
            int cx, int cy, int cz, boolean open, boolean solid) {
        var result = new ArrayList<StructureEvidence.Marker>();
        for (int x = -8; x <= 8; x++)
            for (int y = -8; y <= 8; y++)
                for (int z = -8; z <= 8; z++) {
                    if (open && x > 0 && Math.abs(y) < 2 && Math.abs(z) < 3) continue;
                    double radius = Math.sqrt(x * x + y * y * 1.15 + z * z * .85);
                    // Slightly lumpy ellipsoid, with an outward calcite layer.
                    double edge = 5.5 + .3 * Math.sin(x * 1.1 + z * .7);
                    if (radius <= edge + 1 && (solid || radius >= edge - 1.2)) {
                        result.add(
                                new StructureEvidence.Marker(
                                        AMETHYST_GEODE,
                                        new BlockPos(cx + x, cy + y, cz + z),
                                        false,
                                        radius > edge));
                    }
                }
        return result;
    }

    private static List<StructureEvidence.Found> locate(List<StructureEvidence.Marker> markers) {
        return StructureEvidence.locate(markers, EnumSet.of(AMETHYST_GEODE));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
