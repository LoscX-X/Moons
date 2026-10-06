package com.blanoir.moons.client.module.impl.combat.silentaura.latest;

import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatReach;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;

/** Latest's Block is a visual pose and never owns a server use request. */
public final class LatestBlock {
    private LatestBlock() {}

    public static boolean shouldRender(Minecraft client) {
        if (!SilentAuraConfig.enabled()
                || !SilentAuraConfig.block()
                || !ClientReady.aliveGameplay(client)
                || !client.player.getMainHandItem().is(ItemTags.SWORDS)
                || !SilentAuraRuntime.activationHeld(client)) return false;
        var target = SilentAuraRuntime.currentTarget(client);
        return target != null
                && target.isAlive()
                && client.level.getEntity(target.getId()) == target
                && CombatReach.within(client, target, SilentAuraConfig.blockRange());
    }
}
