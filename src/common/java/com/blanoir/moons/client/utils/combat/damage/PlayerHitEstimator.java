package com.blanoir.moons.client.utils.combat.damage;

import com.blanoir.moons.client.config.feature.HitEstimateSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/** Expected hits from native legacy damage, armor, enchants and recorded critical rate. */
public final class PlayerHitEstimator {
    public static final int UNKNOWN = -1;
    public static final int UNREACHABLE = Integer.MAX_VALUE;

    private PlayerHitEstimator() {}

    public static int hitsToKill(Minecraft client, EntityPlayer target, float resolvedHealth) {
        if (client == null || client.thePlayer == null || client.theWorld == null || target == null)
            return UNKNOWN;
        float health = Math.max(0, resolvedHealth) + Math.max(0, target.getAbsorptionAmount());
        if (health <= 0) return 0;
        double critical = HitEstimateSettings.criticalRate();
        double damage =
                LegacyDamage.melee(client.thePlayer, target, false) * (1 - critical)
                        + LegacyDamage.melee(client.thePlayer, target, true) * critical;
        return !Double.isFinite(damage) || damage <= 1e-4
                ? UNREACHABLE
                : Math.max(1, (int) Math.ceil(health / damage));
    }

    public static String text(Minecraft client, EntityPlayer target, float resolvedHealth) {
        int hits = hitsToKill(client, target, resolvedHealth);
        return hits == UNKNOWN ? "Hit: ?" : hits == UNREACHABLE ? "Hit: ∞" : "Hit: " + hits;
    }
}
