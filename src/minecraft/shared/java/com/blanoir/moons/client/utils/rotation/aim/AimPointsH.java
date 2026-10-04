package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * H: GodBridge side-face selection. Tries the preferred ray first, then ordered yaw/height
 * probes while retaining the bridge direction. Horizontal faces only; first valid probe wins.
 * The chosen ray is re-traced exactly as before. Caller supplies quantization; no world or
 * movement history is retained. This constrained policy is distinct from G's minimum-score scan.
 */
public final class AimPointsH {
    private AimPointsH() {}

    private static final float[] YAW_OFFSETS = {0, .5F, -.5F, 1, -1, 2, -2, 4, -4, 8, -8, 12, -12};
    private static final double[] HEIGHTS = {.75, .5, .9, .25, .1};

    public static BlockAim resolve(
            PlacementRaycast rays,
            Minecraft client,
            BlockTarget target,
            Vec3 eye,
            Rotation preferred,
            double range,
            QuantizerA.Adapter quantizer) {
        Rotation rotation =
                sideAim(
                        eye,
                        preferred,
                        target.face(),
                        height ->
                                BlockPlacementUtils.facePoint(
                                        client, target.support(), target.face(), .5, height),
                        raw -> quantizer.relative(preferred, raw),
                        candidate ->
                                insideFace(
                                        rays.traceFace(
                                                client,
                                                eye,
                                                candidate.yaw(),
                                                candidate.pitch(),
                                                range,
                                                target.support(),
                                                target.face()),
                                        target));
        if (rotation == null) return null;
        MovingObjectPosition hit =
                rays.traceFace(
                        client,
                        eye,
                        rotation.yaw(),
                        rotation.pitch(),
                        range,
                        target.support(),
                        target.face());
        return insideFace(hit, target) ? new BlockAim(target, rotation, hit) : null;
    }

    /** A perfect diagonal can hit the shared edge of two faces; choose a small interior margin. */
    public static boolean insideFace(MovingObjectPosition hit, BlockTarget target) {
        if (hit == null) return false;
        double side =
                target.face().getAxis() == EnumFacing.Axis.X
                        ? hit.hitVec.zCoord - target.support().getZ()
                        : hit.hitVec.xCoord - target.support().getX();
        return side >= .015 && side <= .985;
    }

    private static Rotation sideAim(
            Vec3 eye,
            Rotation preferred,
            EnumFacing face,
            Function<Double, Vec3> facePoint,
            UnaryOperator<Rotation> quantize,
            java.util.function.Predicate<Rotation> reachable) {
        if (face.getAxis() == EnumFacing.Axis.Y) return null;
        Rotation fixed = quantize.apply(preferred);
        if (reachable.test(fixed)) return fixed;
        Vec3 center = facePoint.apply(.5);
        for (float offset : YAW_OFFSETS) {
            float yaw =
                    quantize.apply(new Rotation(preferred.yaw() + offset, preferred.pitch())).yaw();
            Vec3 direction = VecMath.directionFromRotation(0, yaw);
            double component =
                    face.getAxis() == EnumFacing.Axis.X ? direction.xCoord : direction.zCoord;
            double normal =
                    face.getAxis() == EnumFacing.Axis.X
                            ? face.getFrontOffsetX()
                            : face.getFrontOffsetZ();
            if (component * normal >= -1.0E-6) continue;
            double distance =
                    (face.getAxis() == EnumFacing.Axis.X
                                    ? center.xCoord - eye.xCoord
                                    : center.zCoord - eye.zCoord)
                            / component;
            if (distance <= 1.0E-5) continue;
            for (double height : HEIGHTS) {
                Vec3 point = facePoint.apply(height);
                float pitch =
                        (float) Math.toDegrees(Math.atan2(eye.yCoord - point.yCoord, distance));
                if (pitch <= 0 || pitch >= 90) continue;
                Rotation candidate = quantize.apply(new Rotation(yaw, pitch));
                if (reachable.test(candidate)) return candidate;
            }
        }
        return null;
    }
}
