package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.module.impl.combat.silentaura.latest.LatestBlock;
import com.blanoir.moons.client.module.impl.combat.silentaura.legacy.LegacyBlock;

import net.minecraft.client.Minecraft;

/** Stable animation/settings facade; each combat mode owns its blocking behavior. */
public final class SilentAuraBlock {
    private SilentAuraBlock() {}

    public static void init() {
        LegacyBlock.init();
    }

    public static boolean controls(Minecraft client) {
        return SilentAuraRuntime.activationHeld(client);
    }

    public static boolean shouldRenderBlock(Minecraft client) {
        return SilentAuraConfig.legacyCombat()
                ? LegacyBlock.shouldRenderBlock(client)
                : LatestBlock.shouldRender(client);
    }

    public static boolean attackAnimationOnly() {
        return SilentAuraConfig.legacyCombat() && LegacyBlock.attackAnimationOnly();
    }

    public static double animationProgress() {
        return LegacyBlock.animationProgress();
    }

    public static void reset(Minecraft client) {
        LegacyBlock.reset(client);
    }

    public static boolean isEnabled() {
        return SilentAuraConfig.enabled() && SilentAuraConfig.block();
    }
}
