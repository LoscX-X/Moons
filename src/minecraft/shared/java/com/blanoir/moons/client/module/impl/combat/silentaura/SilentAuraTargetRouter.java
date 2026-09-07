package com.blanoir.moons.client.module.impl.combat.silentaura;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Keeps target and aim-point history independent for every aim mode. */
final class SilentAuraTargetRouter {
    private final SilentAuraTargetSelector lock = new SilentAuraTargetSelector(true);
    private final SilentAuraTargetSelector balance = new SilentAuraTargetSelector(false);
    private final SilentAuraTargetSelector fullLock = new SilentAuraTargetSelector(true, true);

    private SilentAuraTargetSelector activeSelector() {
        return SilentAuraConfig.fullLockMode()
                ? fullLock
                : SilentAuraConfig.lockMode() ? lock : balance;
    }

    LivingEntity select(Minecraft client, Vec3 referenceLook) {
        return activeSelector().select(client, referenceLook);
    }

    LivingEntity current(Minecraft client) {
        return activeSelector().current(client);
    }

    Vec3 aimPoint(Minecraft client, LivingEntity target, Vec3 look) {
        return activeSelector().aimPoint(client, target, look);
    }

    void onAttack(Minecraft client, LivingEntity attacked) {
        activeSelector().onAttack(client, attacked);
    }

    void clear() {
        lock.clear();
        balance.clear();
        fullLock.clear();
    }
}
