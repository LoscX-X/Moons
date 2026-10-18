package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.combat.PickResultEvent;
import com.blanoir.moons.client.input.PhysicalInput;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.registry.RegistryLists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Removes selected block outlines from manual picking, without changing world or collision data. */
public final class Ignore {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("ignore.enabled").defaultValue(false).build();
    private static final BooleanSetting SIGNS =
            new BooleanSetting.Builder().name("ignore.signs").defaultValue(true).build();
    private static final BooleanSetting ANTI_OBSIDIAN =
            new BooleanSetting.Builder().name("ignore.antiObsidian").defaultValue(false).build();
    private static final ModeSetting<AntiObsidian.Target> ESCAPE_TARGET =
            new ModeSetting.Builder<AntiObsidian.Target>()
                    .name("ignore.antiObsidianTarget")
                    .defaultValue(AntiObsidian.Target.AUTO)
                    .option(AntiObsidian.Target.AUTO, "auto")
                    .option(AntiObsidian.Target.BELOW, "below")
                    .option(AntiObsidian.Target.FRONT, "front")
                    .build();
    private static final StringSetting BLOCKS =
            new StringSetting.Builder().name("ignore.blocks").defaultValue("[]").build();
    private static String cachedBlocks;
    private static JsonArray entries = new JsonArray();
    private static Set<Block> blocks = Set.of();
    private static LocalPlayer escapePlayer;
    private static BlockPos escapeSupport;

    private Ignore() {}

    public static void init() {
        EventBus.PICK_RESULT.register("Ignore.pick", EventPriority.HIGHEST, Ignore::pick);
        EventBus.CLIENT_CONTEXT_CHANGED.register("Ignore.context", event -> resetEscape());
        EventBus.ATTACK_INPUT_PRE.register(
                "Ignore.escapeAttack",
                EventPriority.HIGHEST,
                event -> {
                    if (event.isCancelled()) return;
                    BlockHitResult target = escapeTarget(event.client());
                    if (target != null) {
                        event.client().hitResult = target;
                        event.client().crosshairPickEntity = null;
                    }
                });
    }

    private static void pick(PickResultEvent event) {
        Minecraft client = event.client();
        if (!ENABLED.get() || !ClientReady.aliveGameplay(client)) return;
        BlockHitResult escape = escapeTarget(client);
        if (escape != null) {
            event.result(escape);
            return;
        }
        if (!(event.result() instanceof BlockHitResult original)
                || original.getType() != HitResult.Type.BLOCK) return;
        refreshBlocks();
        if (!ignored(client.level.getBlockState(original.getBlockPos()))) return;
        Entity camera = client.getCameraEntity();
        if (camera == null) return;
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 start = camera.getEyePosition(partial);
        double blockRange = client.player.blockInteractionRange();
        Vec3 end = start.add(camera.getViewVector(partial).scale(blockRange));
        BlockHitResult blockHit = clip(client.level, camera, start, end, Ignore::ignored);
        // Keep vanilla's entity priority and range after removing the selected obstruction.
        double entityRange = client.player.entityInteractionRange();
        double maxDistance = Math.min(entityRange * entityRange, start.distanceToSqr(end));
        if (blockHit.getType() == HitResult.Type.BLOCK)
            maxDistance = Math.min(maxDistance, start.distanceToSqr(blockHit.getLocation()));
        var entityHit =
                ProjectileUtil.getEntityHitResult(
                        camera,
                        start,
                        end,
                        new AABB(start, end).inflate(1),
                        EntitySelector.CAN_BE_PICKED,
                        maxDistance);
        event.result(entityHit == null ? blockHit : entityHit);
    }

    private static BlockHitResult escapeTarget(Minecraft client) {
        if (!ENABLED.get()
                || !ANTI_OBSIDIAN.get()
                || !ClientReady.aliveGameplay(client)
                || !client.isWindowActive()
                || client.isPaused()
                || !client.options.keyAttack.isDown()
                || !PhysicalInput.isGameplayKeyDown(client, client.options.keyAttack)
                || client.player.isUsingItem()
                || client.player.isSpectator()
                || client.getCameraEntity() != client.player) {
            resetEscape();
            return null;
        }
        if (escapePlayer != client.player) resetEscape();
        escapePlayer = client.player;
        refreshBlocks();
        BlockHitResult target =
                AntiObsidian.target(
                        client.level,
                        client.player,
                        client.player.getBoundingBox(),
                        client.player.getEyePosition(),
                        client.player.getViewVector(1),
                        client.player.blockInteractionRange(),
                        ESCAPE_TARGET.get(),
                        (state, pos) -> ignored(state),
                        escapeSupport);
        escapeSupport =
                target != null && target.getDirection() == net.minecraft.core.Direction.UP
                        ? target.getBlockPos()
                        : null;
        return target;
    }

    private static void resetEscape() {
        escapePlayer = null;
        escapeSupport = null;
    }

    static BlockHitResult clip(
            BlockGetter world, Entity source, Vec3 start, Vec3 end, Predicate<BlockState> ignored) {
        return clip(world, source, start, end, (state, pos) -> ignored.test(state));
    }

    static BlockHitResult clip(
            BlockGetter world,
            Entity source,
            Vec3 start,
            Vec3 end,
            BiPredicate<BlockState, BlockPos> ignored) {
        return world.clip(
                new ClipContext(
                        start,
                        end,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        source == null ? CollisionContext.empty() : CollisionContext.of(source)) {
                    @Override
                    public VoxelShape getBlockShape(
                            BlockState state, BlockGetter level, BlockPos pos) {
                        return ignored.test(state, pos)
                                ? Shapes.empty()
                                : super.getBlockShape(state, level, pos);
                    }
                });
    }

    private static boolean ignored(BlockState state) {
        return SIGNS.get() && state.is(BlockTags.ALL_SIGNS) || blocks.contains(state.getBlock());
    }

    private static void refreshBlocks() {
        String configured = BLOCKS.get();
        if (configured.equals(cachedBlocks)) return;
        JsonArray next = new JsonArray();
        try {
            JsonElement parsed = JsonParser.parseString(configured);
            if (RegistryLists.valid("block_list", parsed)) next = parsed.getAsJsonArray();
        } catch (RuntimeException ignored) {
            // Malformed legacy settings cannot affect normal picking.
        }
        var selected = new HashSet<Block>();
        for (JsonElement entry : next) {
            var object = entry.getAsJsonObject();
            if (object.get("enabled").getAsBoolean())
                selected.add(
                        BuiltInRegistries.BLOCK
                                .getOptional(Identifier.parse(object.get("id").getAsString()))
                                .orElseThrow());
        }
        blocks = Set.copyOf(selected);
        entries = next;
        cachedBlocks = configured;
    }

    public static JsonArray selectedBlocks() {
        refreshBlocks();
        return entries.deepCopy();
    }

    public static void setSelectedBlocks(Minecraft client, JsonElement value) {
        if (!RegistryLists.valid("block_list", value))
            throw new IllegalArgumentException("Invalid ignored block list");
        BLOCKS.set(value.toString());
        refreshBlocks();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        resetEscape();
        return 1;
    }

    public static int setSigns(Minecraft client, boolean value) {
        SIGNS.set(value);
        return 1;
    }

    public static boolean antiObsidianEnabled() {
        return ANTI_OBSIDIAN.get();
    }

    public static int setAntiObsidian(Minecraft client, boolean value) {
        ANTI_OBSIDIAN.set(value);
        resetEscape();
        return 1;
    }

    public static List<String> antiObsidianTargets() {
        return ESCAPE_TARGET.optionIds();
    }

    public static int setAntiObsidianTarget(Minecraft client, String value) {
        ESCAPE_TARGET.deserialize(value);
        resetEscape();
        return 1;
    }

    public static String statusTag() {
        return ANTI_OBSIDIAN.get() ? "AntiObsidian" : SIGNS.get() ? "Signs" : "Blocks";
    }
}
