package com.blanoir.moons.client.utils.combat;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;

/** A main-hand use request for old-combat plugins on the current protocol. */
public final class BlockingUse {
    private LocalPlayer player;
    private ClientLevel level;
    private ClientPacketListener connection;
    private int slot = -1;

    public boolean owned() {
        return player != null;
    }

    public boolean matches(Minecraft client) {
        return ClientReady.world(client)
                && client.player == player
                && client.level == level
                && client.getConnection() == connection
                && client.player.getInventory().getSelectedSlot() == slot
                && client.player.getMainHandItem().is(ItemTags.SWORDS);
    }

    public boolean canStart(Minecraft client) {
        return ClientReady.gameplay(client)
                && client.getConnection() != null
                && !client.player.isUsingItem()
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && GameAccess.rightClickDelay(client) == 0;
    }

    public boolean start(Minecraft client, Rotation rotation) {
        if (matches(client)) return true;
        if (!canStart(client)) return false;
        discard();
        var listener = client.getConnection();
        int selected = client.player.getInventory().getSelectedSlot();
        // Keep vanilla's sent-slot cache in sync; an unchanged slot emits no packet.
        GameAccess.syncCarriedItem(client.gameMode);
        // Minecraft.startUseItem starts this cooldown even when a sword's local use returns PASS.
        GameAccess.rightClickDelay(client, 4);
        GameAccess.withPredictionSequence(
                client.level,
                sequence ->
                        listener.send(
                                new ServerboundUseItemPacket(
                                        InteractionHand.MAIN_HAND,
                                        sequence,
                                        rotation.yaw(),
                                        rotation.pitch())));
        player = client.player;
        level = client.level;
        connection = listener;
        slot = selected;
        return true;
    }

    public boolean stop(Minecraft client) {
        boolean release = matches(client);
        discard();
        if (release)
            client.getConnection()
                    .send(
                            new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                                    BlockPos.ZERO,
                                    Direction.DOWN));
        return release;
    }

    public void discard() {
        player = null;
        level = null;
        connection = null;
        slot = -1;
    }
}
