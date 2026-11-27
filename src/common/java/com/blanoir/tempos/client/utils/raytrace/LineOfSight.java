package com.blanoir.tempos.client.utils.raytrace;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Tests visibility between two positions. */
public final class LineOfSight {
    private LineOfSight() {}

    public static boolean canRayTraceTo(Minecraft client, Vec3 eyePos, Vec3 point) {
        return canRayTraceTo(client, eyePos, point, false);
    }

    public static boolean canRayTraceTo(
            Minecraft client, Vec3 eyePos, Vec3 point, boolean throughBlocks) {
        if (client == null
                || client.level == null
                || client.player == null
                || eyePos == null
                || point == null) {
            return false;
        }
        return throughBlocks
                || (BlockRaycast.clipBlocks(client, eyePos, point).getType() == HitResult.Type.MISS
                        && BlockRaycast.firstCobwebHit(client, eyePos, point).isEmpty());
    }
}
