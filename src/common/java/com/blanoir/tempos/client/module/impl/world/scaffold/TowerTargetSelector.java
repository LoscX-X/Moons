package com.blanoir.tempos.client.module.impl.world.scaffold;

import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.utils.rotation.Rotation;
import com.blanoir.tempos.client.utils.rotation.aim.BlockAim;
import com.blanoir.tempos.client.utils.rotation.aim.BlockTarget;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Vanilla Tower target order. Re-sorts supplied supports by support distance, placement
 * distance and UP-face preference, then accepts the first fixed downward ray from I.
 * Keeps this ordering separate from C's nearest-placement-cell scoring.
 */
public final class TowerTargetSelector {
    private TowerTargetSelector() {}

    public static BlockAim select(
            PlacementRayPolicy rays,
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
                                        target.supportDistanceSquared(desiredCenter))
                        .thenComparingDouble(target -> target.placeDistanceSquared(desiredCenter))
                        .thenComparingInt(target -> target.face() == Direction.UP ? 0 : 1));
        for (BlockTarget target : targets) {
            BlockAim aim =
                    FixedDirectionFacePoint.resolve(
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
