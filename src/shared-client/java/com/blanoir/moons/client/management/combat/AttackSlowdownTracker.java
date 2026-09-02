package com.blanoir.moons.client.management.combat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Captures only the local horizontal motion affected by vanilla attack slowdown. */
public final class AttackSlowdownTracker {
    private static final Map<Player, Snapshot> SNAPSHOTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AttackSlowdownTracker() {
    }

    public static void capture(Player player) {
        if (player != null) {
            SNAPSHOTS.put(player, new Snapshot(player.getDeltaMovement(), player.isSprinting()));
        }
    }

    public static boolean replaceVanillaSlowdown(Player player, double horizontalMultiplier) {
        if (player == null) return false;
        Snapshot snapshot = SNAPSHOTS.remove(player);
        if (snapshot == null || !snapshot.sprinting()) return false;
        Vec3 current = player.getDeltaMovement();
        // causeExtraKnockback only applies attack slowdown on the branch that
        // multiplies X/Z by vanilla's 0.6. A zero-knockback call must be left alone.
        if (!approximately(current.x, snapshot.velocity().x * 0.6D)
                || !approximately(current.z, snapshot.velocity().z * 0.6D)) {
            return false;
        }
        player.setDeltaMovement(
                snapshot.velocity().x * horizontalMultiplier,
                current.y,
                snapshot.velocity().z * horizontalMultiplier);
        player.setSprinting(true);
        return true;
    }

    private static boolean approximately(double first, double second) {
        double scale = Math.max(1.0D, Math.max(Math.abs(first), Math.abs(second)));
        return Math.abs(first - second) <= 1.0E-9D * scale;
    }

    public static void discard(Player player) {
        if (player != null) SNAPSHOTS.remove(player);
    }

    private record Snapshot(Vec3 velocity, boolean sprinting) {
    }
}
