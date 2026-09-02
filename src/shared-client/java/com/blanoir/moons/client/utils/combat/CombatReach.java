package com.blanoir.moons.client.utils.combat;

import com.blanoir.moons.client.utils.entity.EntityDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Shared entity-interaction range validation for combat features. */
public final class CombatReach {
    private CombatReach() {
    }

    public static double entityInteractionRange(Minecraft client, double requestedRange) {
        if (client == null || client.player == null || !Double.isFinite(requestedRange)) {
            return 0.0D;
        }
        double vanillaRange = client.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        return Math.max(0.0D, Math.min(requestedRange, vanillaRange));
    }

    public static double scanRange(Minecraft client, double requestedRange, double extraRange) {
        return entityInteractionRange(client, requestedRange)
                + Math.max(0.0D, Double.isFinite(extraRange) ? extraRange : 0.0D);
    }

    public static boolean within(Minecraft client, Entity target, double range) {
        return Double.isFinite(range) && range >= 0.0D
                && EntityDistance.squaredToEntity(client, target) <= range * range;
    }
}
