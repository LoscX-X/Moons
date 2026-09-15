package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

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
                || client.player == null
                || client.level == null
                || client.gameMode == null) {
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
        ItemStack stack = client.player.getMainHandItem();
        if (!stack.isEmpty()) {
            if (stack.is(Items.FISHING_ROD)) {
                return false;
            }
            if (stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (SKIP_OBSIDIAN.get() && block == Blocks.OBSIDIAN) {
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
    public static BlockHitResult placementHit(Minecraft client) {
        if (!ENABLED.get()
                || client.player == null
                || client.level == null
                || (!RAYS.throughEntity() && !RAYS.throughBlocks())
                || !(client.player.getMainHandItem().getItem() instanceof BlockItem)) return null;
        Vec3 eye = client.player.getEyePosition();
        Vec3 end =
                eye.add(client.player.getLookAngle().scale(client.player.blockInteractionRange()));
        BlockHitResult first =
                client.level.clip(
                        new ClipContext(
                                eye,
                                end,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                client.player));
        if (!RAYS.throughBlocks()) {
            return first.getType() == HitResult.Type.BLOCK
                            && !RAYS.entityBlocked(client, eye, first.getLocation())
                    ? first
                    : null;
        }
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos :
                BlockPos.betweenClosed(BlockPos.containing(eye), BlockPos.containing(end))) {
            if (first.getType() == HitResult.Type.BLOCK && pos.equals(first.getBlockPos()))
                continue;
            BlockHitResult candidate = RAYS.clip(client, eye, end, ClipContext.Fluid.NONE, pos);
            if (candidate.getType() != HitResult.Type.BLOCK) continue;
            BlockPlaceContext context =
                    new BlockPlaceContext(
                            new UseOnContext(client.player, InteractionHand.MAIN_HAND, candidate));
            if (!context.canPlace()) continue;
            double distance = eye.distanceToSqr(candidate.getLocation());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best != null
                ? best
                : first.getType() == HitResult.Type.BLOCK
                                && !RAYS.entityBlocked(client, eye, first.getLocation())
                        ? first
                        : null;
    }

    private static boolean canPlaceAtCrosshair(Minecraft client, BlockItem blockItem) {
        BlockHitResult replacement = placementHit(client);
        BlockHitResult hit =
                replacement != null
                        ? replacement
                        : client.hitResult instanceof BlockHitResult original ? original : null;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
        double reach = client.player.blockInteractionRange();
        if (hit.getLocation().distanceToSqr(client.player.getEyePosition()) > reach * reach) {
            return false;
        }

        BlockPlaceContext context =
                new BlockPlaceContext(
                        new UseOnContext(client.player, InteractionHand.MAIN_HAND, hit));
        BlockState state = blockItem.getBlock().getStateForPlacement(context);
        return state != null
                && state.canSurvive(client.level, context.getClickedPos())
                && client.level.isUnobstructed(
                        state, context.getClickedPos(), CollisionContext.of(client.player));
    }

    private static boolean isInteractable(Block block) {
        if (block instanceof BaseEntityBlock
                || block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof BedBlock) {
            return true;
        }
        if (block instanceof DoorBlock) {
            return block != Blocks.IRON_DOOR;
        }
        return block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof FenceBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof JukeboxBlock;
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
