package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class OfflinePlayerDetect {
    private static final Set<UUID> PRINTED_ZOMBIES = new HashSet<>();

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("offlineplayerdetect.enabled")
                    .defaultValue(true)
                    .build();

    private OfflinePlayerDetect() {}

    public static boolean isOfflinePlayerZombie(LivingEntity entity) {
        if (!ENABLED.get() || !(entity instanceof Zombie zombie) || !zombie.hasCustomName()) {
            return false;
        }

        String name = zombie.getName().getString().trim();
        return !name.isEmpty() && !name.equalsIgnoreCase("Zombie") && !name.equals("僵尸");
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) {
            PRINTED_ZOMBIES.clear();
        }
        ClientChat.send(client, "OfflinePlayerDetect " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static void notifyIfNeeded(Minecraft client, LivingEntity entity) {
        var currentPlayer = client == null ? null : client.player;
        if (!isOfflinePlayerZombie(entity) || client == null || currentPlayer == null) {
            return;
        }

        if (!PRINTED_ZOMBIES.add(entity.getUUID())) {
            return;
        }

        BlockPos pos = entity.blockPosition();
        double distance = Math.sqrt(currentPlayer.distanceToSqr(entity));

        ClientChat.send(
                client,
                "OfflinePlayerDetect: "
                        + entity.getName().getString()
                        + " | Distance: "
                        + Math.round(distance)
                        + " | X: "
                        + pos.getX()
                        + " Y: "
                        + pos.getY()
                        + " Z: "
                        + pos.getZ());
    }
}
