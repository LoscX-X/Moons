package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.util.BlockPos;

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

    public static boolean isOfflinePlayerZombie(EntityLivingBase entity) {
        if (!ENABLED.get() || !(entity instanceof EntityZombie zombie) || !zombie.hasCustomName()) {
            return false;
        }

        String name = zombie.getName().trim();
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

    public static void notifyIfNeeded(Minecraft client, EntityLivingBase entity) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (!isOfflinePlayerZombie(entity) || client == null || currentPlayer == null) {
            return;
        }

        if (!PRINTED_ZOMBIES.add(entity.getUniqueID())) {
            return;
        }

        BlockPos pos = entity.getPosition();
        double distance = Math.sqrt(currentPlayer.getDistanceSqToEntity(entity));

        ClientChat.send(
                client,
                "OfflinePlayerDetect: "
                        + entity.getName()
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
