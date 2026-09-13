package com.blanoir.moons.client.utils.combat;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.HashSet;
import java.util.Set;

/** Owns a native sword-use lifecycle. A sword tag alone does not imply blocking support. */
public final class BlockingUse {
    private static final Set<BlockingUse> ACTIVE = new HashSet<>();
    private Minecraft ownerClient;
    private LocalPlayer player;
    private ClientLevel level;
    private ClientPacketListener connection;
    private int slot = -1;
    private ItemStack nativeItem;

    public boolean owned() {
        return player != null;
    }

    public boolean matches(Minecraft client) {
        return sameContext(client)
                && client.player.getInventory().getSelectedSlot() == slot
                && client.player.getMainHandItem().is(ItemTags.SWORDS);
    }

    private boolean sameContext(Minecraft client) {
        return ClientReady.world(client)
                && client.player == player
                && client.level == level
                && client.getConnection() == connection;
    }

    public boolean ownsNativeUse(Minecraft client) {
        return sameContext(client)
                && nativeItem != null
                && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem() == nativeItem;
    }

    /** Called before vanilla key handling; interruption ends the old lifecycle explicitly. */
    public boolean refresh(Minecraft client) {
        if (!owned()) return false;
        if (!matches(client) || !ownsNativeUse(client)) {
            stop(client);
            return true;
        }
        CombatInputController.holdUse(client, CombatInputController.Owner.BLOCKING_USE);
        return false;
    }

    public boolean canStart(Minecraft client) {
        return ClientReady.gameplay(client)
                && client.getConnection() != null
                && !client.player.isUsingItem()
                && !client.player.isSpectator()
                && supportsSwordBlock(client)
                && !client.player.getCooldowns().isOnCooldown(client.player.getMainHandItem())
                && GameAccess.rightClickDelay(client) == 0;
    }

    public static boolean supportsSwordBlock(Minecraft client) {
        return ClientReady.world(client)
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && client.player.getMainHandItem().getUseAnimation() == ItemUseAnimation.BLOCK;
    }

    public boolean start(Minecraft client, Rotation rotation) {
        if (matches(client)) return true;
        if (!canStart(client)) return false;
        discard();
        int selected = client.player.getInventory().getSelectedSlot();
        GameAccess.rightClickDelay(client, 4);
        // Use the installed client's prediction and item-use logic together. Vanilla owns
        // the use speed/sprint modifiers; neither the visual pose nor Legacy mode sets them.
        float yaw = client.player.getYRot(), pitch = client.player.getXRot();
        try {
            client.player.setYRot(rotation.yaw());
            client.player.setXRot(rotation.pitch());
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        } finally {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
        }
        // Native useItem emits a request even if local use fails. Retain that request until
        // refresh releases it, so a failed start cannot leak server use into an attack.
        if (client.player.isUsingItem()
                && client.player.getUsedItemHand() == InteractionHand.MAIN_HAND)
            nativeItem = client.player.getUseItem();
        ownerClient = client;
        player = client.player;
        level = client.level;
        connection = client.getConnection();
        slot = selected;
        ACTIVE.add(this);
        if (nativeItem != null)
            CombatInputController.holdUse(client, CombatInputController.Owner.BLOCKING_USE);
        return true;
    }

    public boolean stop(Minecraft client) {
        boolean nativeUse = ownsNativeUse(client);
        // A foreign use supersedes ours. Never release its server state independently of its
        // local state. A slot change still releases our previous use in the same context.
        boolean release = sameContext(client) && (!player.isUsingItem() || nativeUse);
        if (release && nativeUse && client.gameMode != null) {
            client.gameMode.releaseUsingItem(player);
        } else if (release) {
            client.getConnection()
                    .send(
                            new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                                    BlockPos.ZERO,
                                    Direction.DOWN));
        }
        discard();
        return release;
    }

    public void discard() {
        ACTIVE.remove(this);
        if (ACTIVE.stream().noneMatch(use -> use.nativeItem != null))
            CombatInputController.releaseUse(ownerClient, CombatInputController.Owner.BLOCKING_USE);
        ownerClient = null;
        nativeItem = null;
        player = null;
        level = null;
        connection = null;
        slot = -1;
    }
}
