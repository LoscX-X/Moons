package com.blanoir.moons.client.module.impl.world.structure;

import com.blanoir.moons.client.utils.world.ChunkKey;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Shape-only guesses over immutable occupancy, calibrated against vanilla-generated cavities. */
final class CavityEvidence {
    private static final int MAX_PROBES = 32_768, MAX_REFINEMENTS = 2048, RAY_LIMIT = 9;
    private static final int[][] AXES = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final List<Vec3> DIRECTIONS = directions();

    static List<StructureEvidence.Found> locate(
            List<CavitySnapshot> snapshots, List<StructureEvidence.Found> materialMatches) {
        return locate(snapshots, materialMatches, () -> false);
    }

    static List<StructureEvidence.Found> locate(
            List<CavitySnapshot> snapshots,
            List<StructureEvidence.Found> materialMatches,
            BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || materialMatches.size() >= 512) return List.of();
        Long2ObjectMap<CavitySnapshot> chunks = new Long2ObjectOpenHashMap<>(snapshots.size());
        for (var snapshot : snapshots)
            chunks.put(ChunkKey.pack(snapshot.chunkX, snapshot.chunkZ), snapshot);
        var occupied = new ArrayList<AABB>();
        for (var match : materialMatches)
            if (match.kind() == StructureEvidence.Kind.AMETHYST_GEODE)
                occupied.add(match.bounds().inflate(2));
        var candidates = new ArrayList<Candidate>();
        Set<Vec3> checked = new HashSet<>();
        int probes = 0, refinements = 0;
        search:
        for (var snapshot : snapshots)
            for (int y = CavitySnapshot.MIN_Y / 2 + 2; y < CavitySnapshot.MAX_Y / 2 - 2; y++)
                for (int z = snapshot.chunkZ * 8; z < snapshot.chunkZ * 8 + 8; z++)
                    for (int x = snapshot.chunkX * 8; x < snapshot.chunkX * 8 + 8; x++) {
                        if (cancelled.getAsBoolean()) return List.of();
                        if (!CavitySnapshot.open(snapshot.at(x, y, z))) continue;
                        Vec3 point = point(x, y, z);
                        if (contains(occupied, point)) continue;
                        if (++probes > MAX_PROBES) break search;
                        var seed = seed(chunks, x, y, z);
                        if (seed == null || !checked.add(seed.center)) continue;
                        if (++refinements > MAX_REFINEMENTS) break search;
                        var candidate = refine(chunks, seed);
                        if (candidate != null) candidates.add(candidate);
                    }
        // The first seed is often at a ledge or crack. Keep the best supported center instead.
        candidates.sort(
                Comparator.comparingDouble(Candidate::score)
                        .reversed()
                        .thenComparingDouble(c -> c.bounds.minX)
                        .thenComparingDouble(c -> c.bounds.minY)
                        .thenComparingDouble(c -> c.bounds.minZ));
        var results = new ArrayList<StructureEvidence.Found>();
        for (var candidate : candidates) {
            if (cancelled.getAsBoolean()) return List.of();
            if (overlaps(occupied, candidate.bounds)) continue;
            results.add(
                    new StructureEvidence.Found(
                            StructureEvidence.Kind.AMETHYST_GEODE,
                            candidate.bounds,
                            0,
                            false,
                            true));
            occupied.add(candidate.bounds.inflate(1));
            if (results.size() + materialMatches.size() >= 512) break;
        }
        return List.copyOf(results);
    }

    private static boolean contains(List<AABB> boxes, Vec3 point) {
        for (AABB box : boxes) if (box.contains(point)) return true;
        return false;
    }

    private static boolean overlaps(List<AABB> boxes, AABB candidate) {
        for (AABB box : boxes) if (box.intersects(candidate)) return true;
        return false;
    }

    private record Seed(Vec3 center, double rx, double ry, double rz) {}

    private record Candidate(AABB bounds, double score) {}

    private record Fit(Seed sphere, Seed oval) {}

    private static Seed seed(Long2ObjectMap<CavitySnapshot> chunks, int x, int y, int z) {
        double[] sides = axes(chunks, x, y, z);
        if (sides == null) return null;
        int cx = x + shift(sides[0], sides[1]),
                cy = y + shift(sides[2], sides[3]),
                cz = z + shift(sides[4], sides[5]);
        if (!CavitySnapshot.open(at(chunks, cx, cy, cz))) return null;
        sides = axes(chunks, cx, cy, cz);
        if (sides == null) return null;
        double rx = radius(sides[0], sides[1]),
                ry = radius(sides[2], sides[3]),
                rz = radius(sides[4], sides[5]);
        double min = Math.min(rx, Math.min(ry, rz)), max = Math.max(rx, Math.max(ry, rz));
        // Vanilla's filling field makes a small chamber. The old 26-block allowance accepted caves.
        if (min < 1.5 || max > 4.5 || max > min * 1.65) return null;
        for (int i = 0; i < 6; i += 2)
            if (sides[i] > 0 && sides[i + 1] > 0 && Math.abs(sides[i] - sides[i + 1]) > 2)
                return null;
        return new Seed(new Vec3(cx, cy, cz), rx, ry, rz);
    }

    private static Candidate refine(Long2ObjectMap<CavitySnapshot> chunks, Seed seed) {
        var fit = fit(chunks, seed);
        if (fit == null) return null;
        var sphere = validate(chunks, fit.sphere);
        if (fit.sphere.equals(fit.oval)) return sphere;
        var oval = validate(chunks, fit.oval);
        return sphere == null ? oval : oval == null || sphere.score >= oval.score ? sphere : oval;
    }

    private static Candidate validate(Long2ObjectMap<CavitySnapshot> chunks, Seed seed) {
        Vec3 center = seed.center;
        int interior = 0, air = 0;
        // Coarse seeds must not hide obstacles on odd block coordinates in the fitted core.
        for (int x = (int) Math.floor((center.x - seed.rx) * 2); x <= (center.x + seed.rx) * 2; x++)
            for (int y = (int) Math.floor((center.y - seed.ry) * 2);
                    y <= (center.y + seed.ry) * 2;
                    y++)
                for (int z = (int) Math.floor((center.z - seed.rz) * 2);
                        z <= (center.z + seed.rz) * 2;
                        z++) {
                    double nx = (x * .5 - center.x) / seed.rx,
                            ny = (y * .5 - center.y) / seed.ry,
                            nz = (z * .5 - center.z) / seed.rz;
                    if (nx * nx + ny * ny + nz * nz > .6) continue;
                    var snapshot = chunks.get(ChunkKey.pack(x >> 4, z >> 4));
                    byte cell =
                            snapshot == null ? CavitySnapshot.UNKNOWN : snapshot.atBlock(x, y, z);
                    if (cell == CavitySnapshot.UNKNOWN) return null;
                    interior++;
                    if (CavitySnapshot.open(cell)) air++;
                }
        if (interior < 7 || air < interior * .9) return null;
        int openings = 0, walls = 0, curved = 0, thick = 0, premature = 0;
        double missingX = 0, missingY = 0, missingZ = 0, error = 0;
        double[] residuals = new double[DIRECTIONS.size()];
        for (Vec3 direction : DIRECTIONS) {
            double expected =
                    1
                            / Math.sqrt(
                                    direction.x * direction.x / (seed.rx * seed.rx)
                                            + direction.y * direction.y / (seed.ry * seed.ry)
                                            + direction.z * direction.z / (seed.rz * seed.rz));
            double hit = trace(chunks, center, direction, expected + 2);
            if (hit == -2) return null;
            if (hit < 0 || hit > expected + 1.25) {
                openings++;
                missingX += direction.x;
                missingY += direction.y;
                missingZ += direction.z;
                continue;
            }
            walls++;
            double residual = Math.abs(hit - expected);
            residuals[walls - 1] = residual;
            if (hit < expected * .6) premature++;
            if (residual <= Math.max(.75, expected * .25)) {
                curved++;
                error += residual / expected;
                if (cellAt(chunks, center, direction, hit + .85) == CavitySnapshot.WALL) thick++;
            }
        }
        if (walls < 56 || premature > 4 || curved < walls * .86 || thick < curved * .8) return null;
        // Validate the entire retained wall distribution, including points discarded by
        // the robust fit. A stepped cave must not pass by fitting just its smooth patches.
        Arrays.sort(residuals, 0, walls);
        double median = residuals[walls / 2], tail = residuals[(int) (walls * .9)];
        // Distances are in two-block units. Allow roughly half a voxel of median
        // error and just over one voxel at the noisy outer tail of vanilla's field.
        if (median > .275 || tail > .6 || median + tail > .75) return null;
        // A cut is one coherent missing patch, not unrelated holes between cave ledges.
        if (openings > 6
                && Math.sqrt(missingX * missingX + missingY * missingY + missingZ * missingZ)
                                / openings
                        < .58) return null;
        Vec3 world = point(center.x, center.y, center.z);
        // Vanilla places the origin at bottom+6..30 and its field sources at +4..6.
        // Include a margin for the fitted center drifting along a cut.
        if (world.y < -58 || world.y > 38) return null;
        AABB bounds =
                new AABB(
                        world.x - seed.rx * 2,
                        world.y - seed.ry * 2,
                        world.z - seed.rz * 2,
                        world.x + seed.rx * 2,
                        world.y + seed.ry * 2,
                        world.z + seed.rz * 2);
        return new Candidate(
                bounds,
                (double) curved / walls
                        + (double) air / interior
                        + (double) walls / DIRECTIONS.size() * .2
                        - error / Math.max(1, curved));
    }

    private static double[] axes(Long2ObjectMap<CavitySnapshot> chunks, int x, int y, int z) {
        double[] result = new double[6];
        int open = 0;
        for (int i = 0; i < 6; i++) {
            int[] d = AXES[i];
            double hit = -1;
            for (int distance = 1; distance <= RAY_LIMIT; distance++) {
                byte cell =
                        at(chunks, x + d[0] * distance, y + d[1] * distance, z + d[2] * distance);
                if (cell == CavitySnapshot.WALL) {
                    hit = distance - .5;
                    break;
                }
                if (!CavitySnapshot.open(cell)) return null;
            }
            if (hit < 0 && ++open > 3) return null;
            result[i] = hit;
        }
        for (int i = 0; i < 6; i += 2) if (result[i] < 0 && result[i + 1] < 0) return null;
        return result;
    }

    private static double trace(
            Long2ObjectMap<CavitySnapshot> chunks, Vec3 center, Vec3 direction, double limit) {
        for (double distance = .5; distance <= limit; distance += .25) {
            byte cell = cellAt(chunks, center, direction, distance);
            if (cell == CavitySnapshot.WALL) return distance - .125;
            if (!CavitySnapshot.open(cell)) return -2;
        }
        return -1;
    }

    private static Fit fit(Long2ObjectMap<CavitySnapshot> chunks, Seed seed) {
        var points = new ArrayList<Vec3>();
        double limit = Math.min(7, Math.max(seed.rx, Math.max(seed.ry, seed.rz)) + 2);
        for (Vec3 direction : DIRECTIONS) {
            double hit = trace(chunks, seed.center, direction, limit);
            if (hit == -2) return null;
            if (hit > 0) points.add(direction.scale(hit));
        }
        if (points.size() < 56) return null;
        double[] errors = new double[points.size()];
        double threshold = Double.POSITIVE_INFINITY;
        Vec3 center = Vec3.ZERO;
        double radius = 0;
        for (int iteration = 0; iteration < 4; iteration++) {
            double[][] equations = new double[4][5];
            int kept = 0;
            for (int i = 0; i < points.size(); i++) {
                if (errors[i] > threshold) continue;
                Vec3 p = points.get(i);
                double[] row = {2 * p.x, 2 * p.y, 2 * p.z, 1};
                double square = p.lengthSqr();
                for (int a = 0; a < 4; a++) {
                    for (int b = 0; b < 4; b++) equations[a][b] += row[a] * row[b];
                    equations[a][4] += row[a] * square;
                }
                kept++;
            }
            if (kept < 40 || !solve(equations)) return null;
            center = new Vec3(equations[0][4], equations[1][4], equations[2][4]);
            double square = equations[3][4] + center.lengthSqr();
            if (square <= 0) return null;
            radius = Math.sqrt(square);
            for (int i = 0; i < points.size(); i++)
                errors[i] = Math.abs(points.get(i).distanceTo(center) - radius);
            double[] ordered = errors.clone();
            Arrays.sort(ordered);
            threshold = Math.max(.45, ordered[(int) (ordered.length * .78)]);
        }
        if (radius < 1.5 || radius > 3.5 || center.length() > 2) return null;
        // Estimate a mild axial deformation only after the robust center is fixed. Letting
        // a free ellipsoid move its center towards a cut produces unstable small chambers.
        double[][] axes = new double[3][4];
        for (int i = 0; i < points.size(); i++) {
            if (errors[i] > Math.max(.75, threshold)) continue;
            var p = points.get(i).subtract(center);
            double[] row = {p.x * p.x, p.y * p.y, p.z * p.z};
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) axes[a][b] += row[a] * row[b];
                axes[a][3] += row[a];
            }
        }
        double rx = radius, ry = radius, rz = radius;
        if (solve(axes) && axes[0][3] > 0 && axes[1][3] > 0 && axes[2][3] > 0) {
            double ax = 1 / Math.sqrt(axes[0][3]),
                    ay = 1 / Math.sqrt(axes[1][3]),
                    az = 1 / Math.sqrt(axes[2][3]);
            double min = Math.min(ax, Math.min(ay, az)), max = Math.max(ax, Math.max(ay, az));
            if (min >= 1.5 && max <= 3.5 && max / min <= 1.4) {
                rx = ax;
                ry = ay;
                rz = az;
            }
        }
        var position = seed.center.add(center);
        return new Fit(new Seed(position, radius, radius, radius), new Seed(position, rx, ry, rz));
    }

    private static boolean solve(double[][] matrix) {
        int size = matrix.length;
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++)
                if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) pivot = row;
            if (Math.abs(matrix[pivot][column]) < 1e-8) return false;
            double[] swap = matrix[column];
            matrix[column] = matrix[pivot];
            matrix[pivot] = swap;
            double divisor = matrix[column][column];
            for (int c = column; c <= size; c++) matrix[column][c] /= divisor;
            for (int row = 0; row < size; row++) {
                if (row == column) continue;
                double factor = matrix[row][column];
                for (int c = column; c <= size; c++) matrix[row][c] -= matrix[column][c] * factor;
            }
        }
        return true;
    }

    private static byte cellAt(
            Long2ObjectMap<CavitySnapshot> chunks, Vec3 center, Vec3 direction, double distance) {
        int x = (int) Math.floor((center.x + direction.x * distance) * 2 + .5);
        int y = (int) Math.floor((center.y + direction.y * distance) * 2 + .5);
        int z = (int) Math.floor((center.z + direction.z * distance) * 2 + .5);
        var snapshot = chunks.get(ChunkKey.pack(x >> 4, z >> 4));
        return snapshot == null ? CavitySnapshot.UNKNOWN : snapshot.atBlock(x, y, z);
    }

    private static List<Vec3> directions() {
        var result = new ArrayList<Vec3>();
        for (int x = -2; x <= 2; x++)
            for (int y = -2; y <= 2; y++)
                for (int z = -2; z <= 2; z++)
                    if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) == 2)
                        result.add(new Vec3(x, y, z).normalize());
        return List.copyOf(result);
    }

    private static int shift(double positive, double negative) {
        return positive < 0 || negative < 0 ? 0 : (int) Math.round((positive - negative) / 2);
    }

    private static double radius(double positive, double negative) {
        return positive < 0 ? negative : negative < 0 ? positive : (positive + negative) / 2;
    }

    private static byte at(Long2ObjectMap<CavitySnapshot> chunks, int x, int y, int z) {
        var snapshot = chunks.get(ChunkKey.pack(x >> 3, z >> 3));
        return snapshot == null ? CavitySnapshot.UNKNOWN : snapshot.at(x, y, z);
    }

    private static Vec3 point(double x, double y, double z) {
        return new Vec3(x * 2 + .5, y * 2 + .5, z * 2 + .5);
    }
}
