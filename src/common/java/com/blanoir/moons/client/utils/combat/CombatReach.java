package com.blanoir.moons.client.utils.combat;

import com.blanoir.moons.client.utils.entity.EntityDistance;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/** Shared entity-interaction range validation for combat features. */
public final class CombatReach {
    private CombatReach() {}

    /** Raw vanilla attribute; callers keep their own null checks and safety margins. */
    public static double vanillaEntityInteractionRange(Player player) {
        return player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
    }

    public static double entityInteractionRange(Minecraft client, double requestedRange) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || !Double.isFinite(requestedRange)) {
            return 0.0D;
        }
        double vanillaRange = vanillaEntityInteractionRange(currentPlayer);
        return Math.max(0.0D, Math.min(requestedRange, vanillaRange));
    }

    public static double scanRange(Minecraft client, double requestedRange, double extraRange) {
        return entityInteractionRange(client, requestedRange)
                + Math.max(0.0D, Double.isFinite(extraRange) ? extraRange : 0.0D);
    }

    public static boolean within(Minecraft client, Entity target, double range) {
        return Double.isFinite(range)
                && range >= 0.0D
                && EntityDistance.squaredToEntity(client, target) <= range * range;
    }

    /** Rejection predicate shared by automatic attack timing and dispatch. */
    public static boolean outsideVanillaRange(Minecraft client, Entity target) {
        if (client == null || client.player == null || target == null) return true;
        return !within(
                client, target, Math.max(0.0D, vanillaEntityInteractionRange(client.player)));
    }
}
