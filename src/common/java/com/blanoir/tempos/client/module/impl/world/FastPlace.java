package com.blanoir.tempos.client.module.impl.world;

import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;

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
@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Sets the delay between repeated item uses or block placements.")
public final class FastPlace extends ClientFeature {
    public static final FastPlace INSTANCE = new FastPlace();

    private final PlacementRayPolicy rays = new PlacementRayPolicy("fastplace");
    private final DecimalFormat delayFormat =
            new DecimalFormat("0.0#", DecimalFormatSymbols.getInstance(Locale.US));

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("fastplace.enabled").defaultValue(false).build();
    private final DoubleSetting delay =
            new DoubleSetting.Builder()
                    .name("fastplace.delay")
                    .defaultValue(1.0)
                    .range(1.0, 3.0)
                    .build();
    private final BooleanSetting blocksOnly =
            new BooleanSetting.Builder().name("fastplace.blocksOnly").defaultValue(true).build();
    private final BooleanSetting placeFix =
            new BooleanSetting.Builder().name("fastplace.placeFix").defaultValue(true).build();
    private final BooleanSetting skipObsidian =
            new BooleanSetting.Builder().name("fastplace.skipObsidian").defaultValue(true).build();
    private final BooleanSetting skipInteractable =
            new BooleanSetting.Builder()
                    .name("fastplace.skipInteractable")
                    .defaultValue(true)
                    .build();

    private long delayMs;

    private FastPlace() {
        super("fastplace", "FastPlace");
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register("FastPlace.tick", event -> tick(event.client()));
    }

    private void tick(Minecraft client) {
        if (!this.enabled.get()) {
            this.delayMs = 0L;
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
            this.delayMs += (long) (50.0 * this.delay.get());
        }
        if (this.delayMs > 0L) {
            this.delayMs -= 50L;
        }
        if (this.delayMs <= 0L && rightClickDelay > 1 && canPlace(client)) {
            GameAccess.rightClickDelay(client, 0);
        }
    }

    private boolean canPlace(Minecraft client) {
        ItemStack stack = client.player.getMainHandItem();
        if (!stack.isEmpty()) {
            if (stack.is(Items.FISHING_ROD)) {
                return false;
            }
            if (stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (this.skipObsidian.get() && block == Blocks.OBSIDIAN) {
                    return false;
                }
                if (this.skipInteractable.get() && isInteractable(block)) {
                    return false;
                }
                if (!this.placeFix.get()) {
                    return true;
                }
                return canPlaceAtCrosshair(client, blockItem);
            }
        }
        return !this.blocksOnly.get();
    }

    /** Manual placement: choose the next usable support behind the first block on the look ray. */
    public BlockHitResult placementHit(Minecraft client) {
        if (!this.enabled.get()
                || client.player == null
                || client.level == null
                || (!this.rays.throughEntity() && !this.rays.throughBlocks())
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
        if (!this.rays.throughBlocks()) {
            return first.getType() == HitResult.Type.BLOCK
                            && !this.rays.entityBlocked(client, eye, first.getLocation())
                    ? first
                    : null;
        }
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos :
                BlockPos.betweenClosed(BlockPos.containing(eye), BlockPos.containing(end))) {
            if (first.getType() == HitResult.Type.BLOCK && pos.equals(first.getBlockPos()))
                continue;
            BlockHitResult candidate =
                    this.rays.clip(client, eye, end, ClipContext.Fluid.NONE, pos);
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
                                && !this.rays.entityBlocked(client, eye, first.getLocation())
                        ? first
                        : null;
    }

    private boolean canPlaceAtCrosshair(Minecraft client, BlockItem blockItem) {
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

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public String statusText() {
        return this.delayFormat.format(this.delay.get());
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        if (!value) {
            this.delayMs = 0L;
        }
        ClientChat.send(client, "FastPlace " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setDelay(Minecraft ignoredClient, double value) {
        this.delay.set(value);
        return 1;
    }

    public int setBlocksOnly(Minecraft ignoredClient, boolean value) {
        this.blocksOnly.set(value);
        return 1;
    }

    public int setPlaceFix(Minecraft ignoredClient, boolean value) {
        this.placeFix.set(value);
        return 1;
    }

    public int setSkipObsidian(Minecraft ignoredClient, boolean value) {
        this.skipObsidian.set(value);
        return 1;
    }

    public int setSkipInteractable(Minecraft ignoredClient, boolean value) {
        this.skipInteractable.set(value);
        return 1;
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append(
                "fastplace",
                new Setting[] {
                    delay.describeStored("delay", "Delay", .05, this::setDelay),
                    blocksOnly.describeStored("blocks_only", "Blocks only", this::setBlocksOnly),
                    placeFix.describeStored("place_fix", "Place fix", this::setPlaceFix),
                    skipObsidian.describeStored(
                            "skip_obsidian", "Skip obsidian", this::setSkipObsidian),
                    skipInteractable.describeStored(
                            "skip_interactable", "Skip interactable", this::setSkipInteractable)
                });
    }
}
