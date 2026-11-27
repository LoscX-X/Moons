package com.blanoir.tempos.client.module.impl.render;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@ModuleInfo(
        category = ModuleCategory.MISC,
        description = "Marks players identified by an offline profile.")
public final class OfflinePlayerDetect extends ClientFeature {
    public static final OfflinePlayerDetect INSTANCE = new OfflinePlayerDetect();

    private final Set<UUID> printedZombies = new HashSet<>();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder()
                    .name("offlineplayerdetect.enabled")
                    .defaultValue(true)
                    .build();

    private OfflinePlayerDetect() {
        super("offlineplayerdetect", "DetectOfflinePlayer");
    }

    public boolean isOfflinePlayerZombie(LivingEntity entity) {
        if (!this.enabled.get() || !(entity instanceof Zombie zombie) || !zombie.hasCustomName()) {
            return false;
        }

        String name = zombie.getName().getString().trim();
        return !name.isEmpty() && !name.equalsIgnoreCase("Zombie") && !name.equals("僵尸");
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        if (!value) {
            this.printedZombies.clear();
        }
        ClientChat.send(client, "OfflinePlayerDetect " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public void notifyIfNeeded(Minecraft client, LivingEntity entity) {
        var currentPlayer = client == null ? null : client.player;
        if (!isOfflinePlayerZombie(entity) || client == null || currentPlayer == null) {
            return;
        }

        if (!this.printedZombies.add(entity.getUUID())) {
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

    @Override
    protected String statusTag() {
        return "Hoplite";
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
