package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.module.impl.combat.silentaura.legacy.LegacyBlock;

import net.minecraft.client.Minecraft;

/** Animation/settings facade for the Legacy blocking lifecycle. */
public final class SilentAuraBlock {
    private SilentAuraBlock() {}

    public static void init() {
        LegacyBlock.init();
    }

    public static boolean controls(Minecraft client) {
        return SilentAuraRuntime.activationHeld(client);
    }

    public static boolean shouldRenderBlock(Minecraft client) {
        return LegacyBlock.shouldRenderBlock(client);
    }

    public static boolean attackAnimationOnly() {
        return LegacyBlock.attackAnimationOnly();
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
