package com.blanoir.moons.client.management.combat;

import com.blanoir.moons.client.compat.math.VecMath;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Captures only the local horizontal motion affected by vanilla attack slowdown. */
public final class AttackSlowdownTracker {
    private static final Map<EntityPlayer, Snapshot> SNAPSHOTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AttackSlowdownTracker() {}

    public static void capture(EntityPlayer player) {
        if (player != null) {
            SNAPSHOTS.put(player, new Snapshot(VecMath.motion(player), player.isSprinting()));
        }
    }

    public static boolean replaceVanillaSlowdown(EntityPlayer player, double horizontalMultiplier) {
        if (player == null) return false;
        Snapshot snapshot = SNAPSHOTS.remove(player);
        if (snapshot == null || !snapshot.sprinting()) return false;
        Vec3 current = VecMath.motion(player);
        // causeExtraKnockback only applies attack slowdown on the branch that
        // multiplies X/Z by vanilla's 0.6. A zero-knockback call must be left alone.
        if (approximately(current.xCoord, snapshot.velocity().xCoord * 0.6D)
                || approximately(current.zCoord, snapshot.velocity().zCoord * 0.6D)) {
            return false;
        }
        VecMath.motion(
                player,
                new Vec3(
                        snapshot.velocity().xCoord * horizontalMultiplier,
                        current.yCoord,
                        snapshot.velocity().zCoord * horizontalMultiplier));
        player.setSprinting(true);
        return true;
    }

    private static boolean approximately(double first, double second) {
        double scale = Math.max(1.0D, Math.max(Math.abs(first), Math.abs(second)));
        return !(Math.abs(first - second) <= 1.0E-9D * scale);
    }

    public static void discard(EntityPlayer player) {
        if (player != null) SNAPSHOTS.remove(player);
    }

    private record Snapshot(Vec3 velocity, boolean sprinting) {}
}
