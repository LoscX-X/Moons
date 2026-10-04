package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * E: Vanilla Tower target order. Re-sorts supplied supports by support distance, placement
 * distance and UP-face preference, then accepts the first fixed downward ray from I.
 * Keeps this ordering separate from C's nearest-placement-cell scoring.
 */
public final class TargetSelectorE {
    private TargetSelectorE() {}

    public static BlockAim select(
            PlacementRaycast rays,
            Minecraft client,
            Vec3 eye,
            BlockPos desired,
            List<BlockTarget> candidates,
            float towerYaw) {
        Vec3 desiredCenter = VecMath.atCenterOf(desired);
        List<BlockTarget> targets = new ArrayList<>(candidates);
        targets.sort(
                Comparator.comparingDouble(
                                (BlockTarget target) ->
                                        target.supportDistanceSquared(desiredCenter))
                        .thenComparingDouble(target -> target.placeDistanceSquared(desiredCenter))
                        .thenComparingInt(target -> target.face() == EnumFacing.UP ? 0 : 1));
        for (BlockTarget target : targets) {
            BlockAim aim =
                    AimPointsI.resolve(
                            rays,
                            client,
                            target,
                            eye,
                            new Rotation(towerYaw, 90.0F),
                            client.playerController.getBlockReachDistance());
            if (aim != null) return aim;
        }
        return null;
    }
}
