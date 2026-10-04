package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.world.LegacyRay;
import com.blanoir.moons.client.utils.world.LegacyWorld;
import com.blanoir.moons.client.utils.world.placement.LegacyPlacement;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockJukebox;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Controls the right-click cooldown. */
public final class FastPlace {
    private static final PlacementRaycast RAYS = new PlacementRaycast("fastplace");
    private static final DecimalFormat DELAY_FORMAT =
            new DecimalFormat("0.0#", DecimalFormatSymbols.getInstance(Locale.US));

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("fastplace.enabled").defaultValue(false).build();
    private static final DoubleSetting DELAY =
            new DoubleSetting.Builder()
                    .name("fastplace.delay")
                    .defaultValue(1.0)
                    .range(1.0, 3.0)
                    .build();
    private static final BooleanSetting BLOCKS_ONLY =
            new BooleanSetting.Builder().name("fastplace.blocksOnly").defaultValue(true).build();
    private static final BooleanSetting PLACE_FIX =
            new BooleanSetting.Builder().name("fastplace.placeFix").defaultValue(true).build();
    private static final BooleanSetting SKIP_OBSIDIAN =
            new BooleanSetting.Builder().name("fastplace.skipObsidian").defaultValue(true).build();
    private static final BooleanSetting SKIP_INTERACTABLE =
            new BooleanSetting.Builder()
                    .name("fastplace.skipInteractable")
                    .defaultValue(true)
                    .build();

    private static long delayMs;

    private FastPlace() {}

    public static void init() {
        EventBus.TICK.register("FastPlace.tick", event -> tick(event.client()));
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get()) {
            delayMs = 0L;
            return;
        }
        if (client == null
                || client.thePlayer == null
                || client.theWorld == null
                || client.playerController == null) {
            return;
        }

        int rightClickDelay = GameAccess.rightClickDelay(client);
        if (rightClickDelay == 4) {
            delayMs += (long) (50.0 * DELAY.get());
        }
        if (delayMs > 0L) {
            delayMs -= 50L;
        }
        if (delayMs <= 0L && rightClickDelay > 1 && canPlace(client)) {
            GameAccess.rightClickDelay(client, 0);
        }
    }

    private static boolean canPlace(Minecraft client) {
        ItemStack stack = client.thePlayer.getHeldItem();
        if (!LegacyItems.empty(stack)) {
            if (LegacyItems.is(stack, Items.fishing_rod)) {
                return false;
            }
            if (stack.getItem() instanceof ItemBlock blockItem) {
                Block block = blockItem.getBlock();
                if (SKIP_OBSIDIAN.get() && block == Blocks.obsidian) {
                    return false;
                }
                if (SKIP_INTERACTABLE.get() && isInteractable(block)) {
                    return false;
                }
                if (!PLACE_FIX.get()) {
                    return true;
                }
                return canPlaceAtCrosshair(client, blockItem);
            }
        }
        return !BLOCKS_ONLY.get();
    }

    /** Manual placement: choose the next usable support behind the first block on the look ray. */
    public static MovingObjectPosition placementHit(Minecraft client) {
        if (!ENABLED.get()
                || client.thePlayer == null
                || client.theWorld == null
                || (!RAYS.throughEntity() && !RAYS.throughBlocks())
                || !(client.thePlayer.getHeldItem().getItem() instanceof ItemBlock)) return null;
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        Vec3 end =
                eye.add(
                        VecMath.scale(
                                client.thePlayer.getLookVec(),
                                Minecraft.getMinecraft().playerController.getBlockReachDistance()));
        MovingObjectPosition first =
                LegacyWorld.clip(
                        client.theWorld,
                        new LegacyRay(
                                eye,
                                end,
                                LegacyRay.Block.OUTLINE,
                                LegacyRay.Fluid.NONE,
                                client.thePlayer));
        if (!RAYS.throughBlocks()) {
            return first.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                            && !RAYS.entityBlocked(client, eye, first.hitVec)
                    ? first
                    : null;
        }
        MovingObjectPosition best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.getAllInBox(new BlockPos(eye), new BlockPos(end))) {
            if (first.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && pos.equals(first.getBlockPos())) continue;
            MovingObjectPosition candidate = RAYS.clip(client, eye, end, LegacyRay.Fluid.NONE, pos);
            if (candidate.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) continue;
            LegacyPlacement.Context context =
                    new LegacyPlacement.Context(client.thePlayer, candidate);
            if (!context.canPlace()) continue;
            double distance = eye.squareDistanceTo(candidate.hitVec);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best != null
                ? best
                : first.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                                && !RAYS.entityBlocked(client, eye, first.hitVec)
                        ? first
                        : null;
    }

    private static boolean canPlaceAtCrosshair(Minecraft client, ItemBlock blockItem) {
        MovingObjectPosition replacement = placementHit(client);
        MovingObjectPosition hit =
                replacement != null
                        ? replacement
                        : client.objectMouseOver instanceof MovingObjectPosition original
                                ? original
                                : null;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK)
            return false;
        double reach = Minecraft.getMinecraft().playerController.getBlockReachDistance();
        if (hit.hitVec.squareDistanceTo(client.thePlayer.getPositionEyes(1.0F)) > reach * reach) {
            return false;
        }

        LegacyPlacement.Context context = new LegacyPlacement.Context(client.thePlayer, hit);
        IBlockState state = LegacyPlacement.state(blockItem.getBlock(), context);
        return state != null
                && LegacyPlacement.survives(state, client.theWorld, context.getClickedPos())
                && LegacyPlacement.unobstructed(
                        client.theWorld, state, context.getClickedPos(), client.thePlayer);
    }

    private static boolean isInteractable(Block block) {
        if (block instanceof BlockContainer
                || block instanceof BlockWorkbench
                || block instanceof BlockAnvil
                || block instanceof BlockBed) {
            return true;
        }
        if (block instanceof BlockDoor) {
            return block != Blocks.iron_door;
        }
        return block instanceof BlockTrapDoor
                || block instanceof BlockFenceGate
                || block instanceof BlockFence
                || block instanceof BlockButton
                || block instanceof BlockLever
                || block instanceof BlockJukebox;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusText() {
        return DELAY_FORMAT.format(DELAY.get());
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) {
            delayMs = 0L;
        }
        ClientChat.send(client, "FastPlace " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setDelay(Minecraft ignoredClient, double value) {
        DELAY.set(value);
        return 1;
    }

    public static int setBlocksOnly(Minecraft ignoredClient, boolean value) {
        BLOCKS_ONLY.set(value);
        return 1;
    }

    public static int setPlaceFix(Minecraft ignoredClient, boolean value) {
        PLACE_FIX.set(value);
        return 1;
    }

    public static int setSkipObsidian(Minecraft ignoredClient, boolean value) {
        SKIP_OBSIDIAN.set(value);
        return 1;
    }

    public static int setSkipInteractable(Minecraft ignoredClient, boolean value) {
        SKIP_INTERACTABLE.set(value);
        return 1;
    }
}
