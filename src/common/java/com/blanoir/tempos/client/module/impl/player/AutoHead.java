package com.blanoir.tempos.client.module.impl.player;


import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.player.HotbarQueries;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

import java.util.function.BooleanSupplier;

/** Uses a hotbar player head for servers that implement UHC head consumables. */
@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Uses a player head from the hotbar as a UHC consumable.")
public final class AutoHead extends ClientFeature {
    public static final AutoHead INSTANCE = new AutoHead();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("autohead.enabled").defaultValue(false).build();
    private final IntSetting health =
            new IntSetting.Builder().name("autohead.health").defaultValue(15).range(1, 20).build();
    private final IntSetting minDelay =
            new IntSetting.Builder()
                    .name("autohead.delay.min")
                    .defaultValue(500)
                    .range(50, 5000)
                    .build();
    private final IntSetting maxDelay =
            new IntSetting.Builder()
                    .name("autohead.delay.max")
                    .defaultValue(1000)
                    .range(50, 5000)
                    .build();
    private boolean initialized;
    private long nextUseNanos;

    private BooleanSupplier scaffoldEnabled = () -> false;

    public void setScaffoldCheck(BooleanSupplier check) {
        scaffoldEnabled = check;
    }

    private AutoHead() {
        super("autohead", "AutoHead");
    }

    @Override
    public void registerEvents() {
        if (this.initialized) return;
        this.initialized = true;
        EventBus.TICK.register("AutoHead.tick", event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "AutoHead.context", event -> this.nextUseNanos = 0);
    }

    private void tick(Minecraft client) {
        if (!isEnabled()
                || client == null
                || client.player == null
                || client.level == null
                || client.gameMode == null
                || client.getConnection() == null) return;
        if (!client.player.isAlive()
                || client.player.isSpectator()
                || MinecraftClientAccess.screen(client) != null
                || client.player.isUsingItem()
                || client.options.keyUse.isDown()
                || client.gameMode.isDestroying()
                || scaffoldEnabled.getAsBoolean()
                || PlacementCoordinator.busy(PlacementCoordinator.Owner.ANTI_WEB)
                || SilentPacketRotation.isBusy()
                || client.player.getHealth() > this.health.get()
                || client.player.getAbsorptionAmount() > 0) return;

        long now = System.nanoTime();
        if (now < this.nextUseNanos) return;
        var inventory = client.player.getInventory();
        int slot = HotbarQueries.firstItem(inventory, Items.PLAYER_HEAD);
        if (slot < 0) return;

        int min = Math.min(this.minDelay.get(), this.maxDelay.get());
        int max = Math.max(this.minDelay.get(), this.maxDelay.get());
        this.nextUseNanos = now + RandomRange.betweenInclusive(min, max) * 1_000_000L;
        var connection = client.getConnection();
        int originalSlot = inventory.getSelectedSlot();
        // Keep the local held item intact, with ordered select/use/restore packets.
        connection.send(new ServerboundSetCarriedItemPacket(slot));
        try {
            GameAccess.withPredictionSequence(
                    client.level,
                    sequence ->
                            connection.send(
                                    new ServerboundUseItemPacket(
                                            InteractionHand.MAIN_HAND,
                                            sequence,
                                            client.player.getYRot(),
                                            client.player.getXRot())));
        } finally {
            connection.send(new ServerboundSetCarriedItemPacket(originalSlot));
        }
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        this.nextUseNanos = 0;
        ClientChat.send(client, "AutoHead " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setHealth(Minecraft client, int value) {
        this.health.set(value);
        return 1;
    }

    public int setDelay(Minecraft client, int min, int max) {
        this.minDelay.set(Math.min(min, max));
        this.maxDelay.set(Math.max(min, max));
        return 1;
    }

    @Override
    protected String statusTag() {
        return "UHC";
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            health.describeStored("health", "Health", 1, this::setHealth),
            minDelay.describeStoredRange("delay", "Delay ms", maxDelay, 50, this::setDelay)
        };
    }
}
