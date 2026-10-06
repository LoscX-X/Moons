package com.blanoir.moons.client.manager.combat;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.HashSet;
import java.util.Set;

/** Owns a native sword-use lifecycle. A sword tag alone does not imply blocking support. */
public final class BlockingUse {
    private static final Set<BlockingUse> ACTIVE = new HashSet<>();
    private Minecraft ownerClient;
    private EntityPlayerSP player;
    private WorldClient level;
    private NetHandlerPlayClient connection;
    private int slot = -1;
    private ItemStack nativeItem;

    public boolean owned() {
        return player != null;
    }

    public boolean matches(Minecraft client) {
        return sameContext(client)
                && client.thePlayer.inventory.currentItem == slot
                && client.thePlayer.getHeldItem() != null
                && client.thePlayer.getHeldItem().getItem() instanceof ItemSword;
    }

    private boolean sameContext(Minecraft client) {
        return ClientReady.world(client)
                && client.thePlayer == player
                && client.theWorld == level
                && client.getNetHandler() == connection;
    }

    public boolean ownsNativeUse(Minecraft client) {
        return sameContext(client)
                && nativeItem != null
                && player.isUsingItem()
                && player.getItemInUse() == nativeItem;
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
                && client.getNetHandler() != null
                && !client.thePlayer.isUsingItem()
                && !client.thePlayer.isSpectator()
                && supportsSwordBlock(client)
                && GameAccess.rightClickDelay(client) == 0;
    }

    public static boolean supportsSwordBlock(Minecraft client) {
        return ClientReady.world(client)
                && client.thePlayer.getHeldItem() != null
                && client.thePlayer.getHeldItem().getItem() instanceof ItemSword
                && client.thePlayer.getHeldItem().getItemUseAction() == EnumAction.BLOCK;
    }

    public boolean start(Minecraft client, Rotation rotation) {
        if (matches(client)) return true;
        if (!canStart(client)) return false;
        discard();
        int selected = client.thePlayer.inventory.currentItem;
        GameAccess.rightClickDelay(client, 4);
        // Use the installed client's prediction and item-use logic together. Vanilla owns
        // the use speed/sprint modifiers; neither the visual pose nor Legacy mode sets them.
        float yaw = client.thePlayer.rotationYaw, pitch = client.thePlayer.rotationPitch;
        try {
            client.thePlayer.rotationYaw = rotation.yaw();
            client.thePlayer.rotationPitch = rotation.pitch();
            client.playerController.sendUseItem(
                    client.thePlayer, client.theWorld, client.thePlayer.getHeldItem());
        } finally {
            client.thePlayer.rotationYaw = yaw;
            client.thePlayer.rotationPitch = pitch;
        }
        // Native useItem emits a request even if local use fails. Retain that request until
        // refresh releases it, so a failed start cannot leak server use into an attack.
        if (client.thePlayer.isUsingItem()) nativeItem = client.thePlayer.getItemInUse();
        ownerClient = client;
        player = client.thePlayer;
        level = client.theWorld;
        connection = client.getNetHandler();
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
        if (release && nativeUse && client.playerController != null) {
            client.playerController.onStoppedUsingItem(player);
        } else if (release) {
            client.getNetHandler()
                    .addToSendQueue(
                            new C07PacketPlayerDigging(
                                    C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                                    BlockPos.ORIGIN,
                                    EnumFacing.DOWN));
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
