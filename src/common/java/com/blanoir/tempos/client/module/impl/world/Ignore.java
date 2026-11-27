package com.blanoir.tempos.client.module.impl.world;


import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.config.settings.StringSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.combat.PickResultEvent;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.blanoir.tempos.client.lifecycle.ClientReady;
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
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Removes selected block outlines from manual picking, without changing world or collision data. */
@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description = "Skips selected blocks when picking a target under the crosshair.")
public final class Ignore extends ClientFeature {
    public static final Ignore INSTANCE = new Ignore();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("ignore.enabled").defaultValue(false).build();
    private final BooleanSetting signs =
            new BooleanSetting.Builder().name("ignore.signs").defaultValue(true).build();
    private final BooleanSetting antiObsidian =
            new BooleanSetting.Builder().name("ignore.antiObsidian").defaultValue(false).build();
    private final ModeSetting<AntiObsidian.Target> escapeTarget =
            new ModeSetting.Builder<AntiObsidian.Target>()
                    .name("ignore.antiObsidianTarget")
                    .defaultValue(AntiObsidian.Target.AUTO)
                    .option(AntiObsidian.Target.AUTO, "auto")
                    .option(AntiObsidian.Target.BELOW, "below")
                    .option(AntiObsidian.Target.FRONT, "front")
                    .build();
    private final StringSetting blocksSetting =
            new StringSetting.Builder().name("ignore.blocks").defaultValue("[]").build();
    private String cachedBlocks;
    private JsonArray entries = new JsonArray();
    private Set<Block> blocks = Set.of();
    private LocalPlayer escapePlayer;
    private BlockPos escapeSupport;

    private Ignore() {
        super("ignore", "Ignore");
    }

    @Override
    public void registerEvents() {
        EventBus.PICK_RESULT.register("Ignore.pick", EventPriority.HIGHEST, this::pick);
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

    private void pick(PickResultEvent event) {
        Minecraft client = event.client();
        if (!this.enabled.get() || !ClientReady.aliveGameplay(client)) return;
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
        BlockHitResult blockHit = clip(client.level, camera, start, end, this::ignored);
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

    private BlockHitResult escapeTarget(Minecraft client) {
        if (!this.enabled.get()
                || !this.antiObsidian.get()
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
        if (this.escapePlayer != client.player) resetEscape();
        this.escapePlayer = client.player;
        refreshBlocks();
        BlockHitResult target =
                AntiObsidian.target(
                        client.level,
                        client.player,
                        client.player.getBoundingBox(),
                        client.player.getEyePosition(),
                        client.player.getViewVector(1),
                        client.player.blockInteractionRange(),
                        this.escapeTarget.get(),
                        (state, pos) -> ignored(state),
                        this.escapeSupport);
        this.escapeSupport =
                target != null && target.getDirection() == net.minecraft.core.Direction.UP
                        ? target.getBlockPos()
                        : null;
        return target;
    }

    private void resetEscape() {
        this.escapePlayer = null;
        this.escapeSupport = null;
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

    private boolean ignored(BlockState state) {
        return this.signs.get() && state.is(BlockTags.ALL_SIGNS)
                || this.blocks.contains(state.getBlock());
    }

    private void refreshBlocks() {
        String configured = this.blocksSetting.get();
        if (configured.equals(this.cachedBlocks)) return;
        JsonArray next = new JsonArray();
        try {
            JsonElement parsed = JsonParser.parseString(configured);
            if (SettingFactory.valid("block_list", parsed)) next = parsed.getAsJsonArray();
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
        this.blocks = Set.copyOf(selected);
        this.entries = next;
        this.cachedBlocks = configured;
    }

    public JsonArray selectedBlocks() {
        refreshBlocks();
        return this.entries.deepCopy();
    }

    public void setSelectedBlocks(Minecraft client, JsonElement value) {
        if (!SettingFactory.valid("block_list", value))
            throw new IllegalArgumentException("Invalid ignored block list");
        this.blocksSetting.set(value.toString());
        refreshBlocks();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        resetEscape();
        return 1;
    }

    public int setSigns(Minecraft client, boolean value) {
        this.signs.set(value);
        return 1;
    }

    public boolean antiObsidianEnabled() {
        return this.antiObsidian.get();
    }

    public int setAntiObsidian(Minecraft client, boolean value) {
        this.antiObsidian.set(value);
        resetEscape();
        return 1;
    }

    public int setAntiObsidianTarget(Minecraft client, String value) {
        this.escapeTarget.deserialize(value);
        resetEscape();
        return 1;
    }

    @Override
    public String statusTag() {
        return this.antiObsidian.get() ? "AntiObsidian" : this.signs.get() ? "Signs" : "Blocks";
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            signs.describeStored("signs", "Signs", this::setSigns),
            antiObsidian.describeStored("anti_obsidian", "AntiObsidian", this::setAntiObsidian),
            escapeTarget
                    .describeStored(
                            "anti_obsidian_target", "Escape target", this::setAntiObsidianTarget)
                    .visibleWhen(this::antiObsidianEnabled),
            SettingFactory.registryList(
                    "blocks",
                    "Extra ignored blocks",
                    "block",
                    this::selectedBlocks,
                    this::setSelectedBlocks,
                    new com.google.gson.JsonArray())
        };
    }
}
