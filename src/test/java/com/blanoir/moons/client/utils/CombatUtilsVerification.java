package com.blanoir.moons.client.utils;

import com.blanoir.moons.client.utils.entity.EntityTypeIds;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.Smoothing;
import com.blanoir.moons.client.utils.rotation.aim.AimPointUtils;
import com.blanoir.moons.client.utils.rotation.aim.HumanAimSimulator;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Boundary contracts for extracted combat calculations; no game session or registry bootstrap. */
public final class CombatUtilsVerification {
    private CombatUtilsVerification() {}

    public static void main(String[] args) {
        scalarAndSmoothing();
        boxCoordinates();
        angleQuantization();
        identifierText();
        System.out.println("COMBAT_UTILS_VERIFIED");
    }

    private static void scalarAndSmoothing() {
        equal(1.5F, MathUtils.approach(1.0F, 4.0F, 0.5F), "float step is bounded");
        equal(
                1.0D + 1.0E-10D,
                MathUtils.approach(1.0D, 4.0D, 1.0E-10D),
                "double step retains sub-float precision");
        equal(2.0D, MathUtils.approach(1.0D, 2.0D, 3.0D), "step cannot overshoot");
        check(Float.isNaN(MathUtils.approach(Float.NaN, 1.0F, 0.1F)), "NaN is not sanitized");
        equal(-4.0D, MathUtils.cubicSmoothStep(2.0D), "polynomial does not clamp caller input");
        equal(0.5D, MathUtils.cubicSmoothStep(0.5D), "cubic midpoint");
        equal(0.0D, Smoothing.exponentialResponse(12.0D, 0.0D), "zero elapsed time");
        equal(0.75D, Smoothing.coefficientForElapsedTicks(0.5D, 2.0D), "two half-retention ticks");
    }

    private static void boxCoordinates() {
        Vec3 point = AimPointUtils.localPoint(new AABB(2, 4, 6, 4, 8, 10), -1, 0.5D, 2);
        check(point.equals(new Vec3(2, 6, 10)), "local coordinates clamp independently");
        equal(0.5D, AimPointUtils.fraction(10, 3, 3), "zero-width axis has midpoint fraction");
        equal(
                0.5D,
                AimPointUtils.fraction(10, 0, 1.0E-6D),
                "degenerate-axis threshold is inclusive");
        equal(1.0D, AimPointUtils.fraction(10, 0, 2), "ordinary fraction clamps to box");
    }

    private static void angleQuantization() {
        equal(181.0F, MathUtils.approachWrapped(179.0F, -179.0F, 4.0F), "short wrapped turn");
        equal(
                181.0F,
                RotationUtils.continuousYaw(181.0F, -179.0F),
                "continuous yaw keeps full turns");
        equal(
                -179.0F,
                RotationUtils.continuousYaw(Float.NaN, -179.0F),
                "invalid reference preserves camera");
        equal(
                181.0F,
                RotationUtils.quantizeYawWithStep(179.0F, -179.0F, 0.25D),
                "yaw quantization wraps demand");
        equal(
                90.0F,
                RotationUtils.quantizePitchWithStep(0.0F, 100.0F, Double.NaN),
                "invalid pitch step still clamps");
        equal(
                200.0F,
                RotationUtils.quantizeYawWithStep(0.0F, 200.0F, 0.0D),
                "invalid yaw step preserves desired domain");
        equal(
                (double) 0.15F,
                RotationUtils.mouseSensitivityStep(0.5D),
                "mouse quantum retains float rounding");
        equal(
                RotationUtils.mouseSensitivityStep(1.0D),
                HumanAimSimulator.mouseSensitivityGcd(2.0D),
                "aim-settings entry point still clamps sensitivity");
        check(
                RotationUtils.mouseSensitivityStep(2.0D)
                        > HumanAimSimulator.mouseSensitivityGcd(2.0D),
                "raw vanilla entry point does not acquire settings clamping");
    }

    private static void identifierText() {
        var zombie = EntityTypeIds.parse("  ZOMBIE  ");
        var creeper = EntityTypeIds.parse("Minecraft:Creeper");
        check(
                zombie != null && zombie.toString().equals("minecraft:zombie"),
                "default namespace and case");
        check(
                EntityTypeIds.parse("bad name") == null && EntityTypeIds.parse(null) == null,
                "invalid and absent identifiers remain null");
        check(
                EntityTypeIds.serialize(List.of(zombie, creeper, zombie))
                        .equals("minecraft:creeper,minecraft:zombie,minecraft:zombie"),
                "serialization sorts without silently deduplicating");
    }

    private static void equal(double expected, double actual, String message) {
        check(
                Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual),
                message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
