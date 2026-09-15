package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

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
        Vec3 desiredCenter = Vec3.atCenterOf(desired);
        List<BlockTarget> targets = new ArrayList<>(candidates);
        targets.sort(
                Comparator.comparingDouble(
                                (BlockTarget target) ->
                                        Vec3.atCenterOf(target.support())
                                                .distanceToSqr(desiredCenter))
                        .thenComparingDouble(
                                target ->
                                        Vec3.atCenterOf(target.placePos())
                                                .distanceToSqr(desiredCenter))
                        .thenComparingInt(target -> target.face() == Direction.UP ? 0 : 1));
        for (BlockTarget target : targets) {
            BlockAim aim =
                    AimPointsI.resolve(
                            rays,
                            client,
                            target,
                            eye,
                            new Rotation(towerYaw, 90.0F),
                            client.player.blockInteractionRange());
            if (aim != null) return aim;
        }
        return null;
    }
}
