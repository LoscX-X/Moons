package com.blanoir.moons.features;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.command.PremiumCheckCommand;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.combat.AttackEntityEvent;
import com.blanoir.moons.client.event.combat.BlockBreakEvent;
import com.blanoir.moons.client.event.combat.PickResultEvent;
import com.blanoir.moons.client.event.frame.WorldRenderDispatch;
import com.blanoir.moons.client.event.movement.LocalPlayerLivingTickEvent;
import com.blanoir.moons.client.event.movement.MovementInputUpdatedEvent;
import com.blanoir.moons.client.event.render.LivingRenderEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.management.rotation.MoveFix;
import com.blanoir.moons.client.management.time.TimerManager;
import com.blanoir.moons.client.module.impl.combat.Reach;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.module.impl.misc.AntiNick;
import com.blanoir.moons.client.module.impl.misc.ArmorHide;
import com.blanoir.moons.client.module.impl.misc.ChatFilter;
import com.blanoir.moons.client.module.impl.misc.FreeLook;
import com.blanoir.moons.client.module.impl.misc.StaticFov;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.module.impl.movement.KeepSprint;
import com.blanoir.moons.client.module.impl.movement.NoJumpDelay;
import com.blanoir.moons.client.module.impl.movement.Sprint;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.module.impl.render.AntiDebuff;
import com.blanoir.moons.client.module.impl.render.Chams;
import com.blanoir.moons.client.module.impl.render.Clip;
import com.blanoir.moons.client.module.impl.render.FullBright;
import com.blanoir.moons.client.module.impl.render.Nametags;
import com.blanoir.moons.client.module.impl.render.Nickname;
import com.blanoir.moons.client.module.impl.render.NicknameShuffle;
import com.blanoir.moons.client.module.impl.render.Scoreboard;
import com.blanoir.moons.client.module.impl.render.xray.XrayTerrain;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.render.VisualPresentation;
import com.blanoir.moons.client.render.model.ModelOverlayRenderer;
import com.blanoir.moons.client.utils.render.SodiumQuadAlpha;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;
import com.blanoir.moons.features.command.ClientCommands;
import com.blanoir.moons.runtime.RuntimeEvents;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.scores.Objective;

/** Applies transformed method hooks used by the built-in client features. */
public final class FeatureHooks {
    private FeatureHooks() {}

    /** Called before the runtime allocates a method-hook event or boxes its values. */
    public static boolean isActive(String id) {
        return switch (id) {
            case "placement.item-ray" -> PlacementRaycast.hasItemRay();
            case "client.timer-speed" -> TimerManager.active(Minecraft.getInstance());
            case "render.antidebuff.blend", "render.antidebuff.fog", "render.antidebuff.sky" ->
                    AntiDebuff.enabled();
            case "xray.block-tessellate.end", "optional.sodium.render-model.end" ->
                    XrayTerrain.isRenderingBackground();
            case "xray.block-tessellate",
                    "optional.sodium.render-model",
                    "optional.sodium.process-quad.force-opaque",
                    "optional.sodium.process-quad.layer",
                    "optional.sodium.process-quad.alpha",
                    "xray.force-opaque",
                    "xray.quad-alpha",
                    "xray.material-layer",
                    "xray.section-quad.alpha",
                    "xray.section-quad.layer",
                    "xray.section-occlusion" ->
                    XrayTerrain.isEnabled();
            case "render.camera-zoom",
                    "render.frustum-visible",
                    "render.clip-occlusion",
                    "optional.sodium.clip-occlusion" ->
                    Clip.isEnabled();
            case "render.full-bright" -> FullBright.isEnabled();
            case "render.static-fov" -> StaticFov.isEnabled();
            case "render.scoreboard" -> Scoreboard.isEnabled();
            case "render.antibot-hide" -> AntiBot.isHideBotActive();
            case "render.silent-aura-animation", "render.silent-aura-animation.replace-vanilla" ->
                    Animations.renderingEnabled();
            case "render.armor-hide",
                    "render.armor-hide.head",
                    "render.armor-hide.head-item",
                    "render.armor-hide.wings" ->
                    ArmorHide.isEnabled();
            case "render.player-nametag" -> Nametags.isEnabled();
            case "render.player-skin" -> NicknameShuffle.isEnabled();
            case "render.chams-submit", "render.chams-submit.end" ->
                    Chams.isChamsEnabled()
                            || com.blanoir.moons.client.render.VisualModelCapture.active()
                            || ArmorHide.isEnabled()
                            || EventBus.LIVING_RENDER_PRE.listenerCount() != 0
                            || EventBus.LIVING_RENDER_POST.listenerCount() != 0;
            case "render.chams-draw",
                    "render.chams-draw-oit",
                    "render.chams-type",
                    "render.chams-equipment",
                    "render.chams-cape",
                    "render.chams-mark-item",
                    "render.chams-item",
                    "render.chams-item.end",
                    "render.chams-item.type",
                    "render.chams-foil.type" ->
                    Chams.isChamsEnabled()
                            || com.blanoir.moons.client.render.VisualModelCapture.requested();
            case "combat.reach.pick" ->
                    Reach.isEnabled() || EventBus.PICK_RESULT.listenerCount() != 0;
            case "render.freelook.turn", "render.freelook.rotation" -> FreeLook.isEnabled();
            default -> true;
        };
    }

    public static void apply(RuntimeEvents.MethodHook hook) {
        switch (hook.id()) {
            case "render.freelook.turn" -> {
                if (hook.argument() instanceof Object[] arguments
                        && arguments.length == 2
                        && arguments[0] instanceof Double horizontal
                        && arguments[1] instanceof Double vertical
                        && FreeLook.turn(hook.owner(), horizontal, vertical)) hook.value(false);
            }
            case "render.freelook.rotation" -> {
                if (hook.argument() instanceof Float index && hook.value() instanceof Float value)
                    hook.value(FreeLook.cameraAngle(hook.owner(), index, value));
            }
            case "placement.item-ray" -> hook.value(PlacementRaycast.itemRay(hook.value()));
            case "client.timer-speed" -> {
                if (hook.owner() instanceof Minecraft client && hook.value() instanceof Float value)
                    hook.value(TimerManager.adjustTickMillis(client, value));
            }
            case "render.antidebuff.blend" -> {
                if (hook.owner() == Minecraft.getInstance().getCameraEntity()
                        && hook.argument() instanceof net.minecraft.core.Holder<?> effect
                        && (effect.equals(net.minecraft.world.effect.MobEffects.NAUSEA)
                                        && AntiDebuff.suppress(
                                                net.minecraft.world.effect.MobEffects.NAUSEA)
                                || effect.equals(net.minecraft.world.effect.MobEffects.DARKNESS)
                                        && AntiDebuff.suppress(
                                                net.minecraft.world.effect.MobEffects.DARKNESS)))
                    hook.value(0.0F);
            }
            case "render.antidebuff.fog" -> {
                if (hook.owner()
                                instanceof
                                net.minecraft.client.renderer.fog.environment
                                                .MobEffectFogEnvironment
                                        fog
                        && AntiDebuff.suppress(fog.getMobEffect())) hook.value(false);
            }
            case "render.antidebuff.sky" -> {
                if (hook.owner() instanceof net.minecraft.client.renderer.GameRenderer renderer
                        && Minecraft.getInstance().getCameraEntity()
                                instanceof LivingEntity entity) {
                    var state =
                            renderer.getGameRenderState()
                                    .levelRenderState
                                    .cameraRenderState
                                    .entityRenderState;
                    if (state != null)
                        state.doesMobEffectBlockSky =
                                entity.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                                                && !AntiDebuff.suppress(
                                                        net.minecraft.world.effect.MobEffects
                                                                .BLINDNESS)
                                        || entity.hasEffect(
                                                        net.minecraft.world.effect.MobEffects
                                                                .DARKNESS)
                                                && !AntiDebuff.suppress(
                                                        net.minecraft.world.effect.MobEffects
                                                                .DARKNESS);
                }
            }
            case "movement.living-ai-step" -> livingAiStep(hook.owner());
            case "movement.keyboard-input" -> keyboardInput(hook.owner());
            case "movement.sprint-start", "movement.sprint-input" -> {
                if (hook.owner() instanceof net.minecraft.client.player.LocalPlayer player) {
                    boolean vanilla = Boolean.TRUE.equals(hook.value());
                    hook.value(
                            !CombatInputController.isSprintSuppressed()
                                    && !Scaffold.shouldSuppressSprint(Minecraft.getInstance())
                                    && (Sprint.shouldSprint(player) || vanilla));
                }
            }
            case "movement.sprint-stop" -> {
                if (hook.owner() instanceof net.minecraft.client.player.LocalPlayer player) {
                    boolean vanilla = Boolean.TRUE.equals(hook.value());
                    hook.value(
                            CombatInputController.isSprintSuppressed()
                                    || Scaffold.shouldSuppressSprint(Minecraft.getInstance())
                                    || (!Sprint.shouldSprint(player) && vanilla));
                }
            }
            case "movement.jump-yaw", "movement.collision-yaw" -> silentYaw(hook);

            case "combat.reach.pick" -> publishPickResult();
            case "combat.block-break-start" -> {
                if (RotationLease.hasSilentRotation()) {
                    if (hook.owner() instanceof MultiPlayerGameMode gameMode) {
                        gameMode.stopDestroyBlock();
                    }
                    hook.value(false);
                    break;
                }
                if (hook.owner() instanceof MultiPlayerGameMode gameMode
                        && hook.argument() instanceof BlockPos position) {
                    BlockBreakEvent event = new BlockBreakEvent(gameMode, position);
                    EventBus.BLOCK_BREAK_PRE.post(event);
                    if (event.isCancelled()) hook.value(false);
                }
                if (SilentAura.shouldSuppressBlockBreaking()) {
                    hook.value(false);
                }
            }
            case "combat.player-extra-knockback" -> KeepSprint.beginAttackSlowdown(hook.owner());
            case "combat.player-extra-knockback.end" ->
                    KeepSprint.finishAttackSlowdown(hook.owner());
            case "combat.player-attack-observe" -> {
                if (hook.owner() == Minecraft.getInstance().player
                        && hook.owner() instanceof Player attacker
                        && hook.argument() instanceof net.minecraft.world.entity.Entity target) {
                    EventBus.ATTACK_ENTITY_PRE.post(new AttackEntityEvent.Pre(attacker, target));
                }
            }
            case "combat.player-attack-observe.end" -> {
                if (hook.owner() == Minecraft.getInstance().player
                        && hook.owner() instanceof Player attacker
                        && hook.argument() instanceof net.minecraft.world.entity.Entity target) {
                    EventBus.ATTACK_ENTITY_POST.post(new AttackEntityEvent.Post(attacker, target));
                }
            }

            case "render.antibot-hide" -> {
                if (hook.argument() instanceof Object[] args
                        && args.length > 0
                        && AntiBot.shouldHideRenderState(args[0])) {
                    hook.value(false);
                }
            }
            case "render.present" -> VisualPresentation.render();
            case "render.scoreboard" -> {
                if (hook.argument() instanceof Object[] values
                        && values.length == 2
                        && values[0] instanceof GuiGraphicsExtractor graphics
                        && values[1] instanceof Objective objective) {
                    hook.value(Scoreboard.render(graphics, objective));
                }
            }
            case "render.scaffold-item-spoof", "render.scaffold-hud-item-spoof" -> {
                if (hook.value() instanceof ItemStack item) hook.value(Scaffold.spoofedItem(item));
            }
            case "render.silent-aura-animation" -> {
                if (hook.argument() instanceof Object[] values
                        && values.length == 4
                        && values[0] instanceof PoseStack pose
                        && values[1] instanceof Number swing
                        && values[2] instanceof Number equip) {
                    Animations.apply(pose, swing.floatValue(), equip.floatValue(), values[3]);
                }
            }
            case "render.silent-aura-animation.replace-vanilla" -> {
                if (Animations.shouldReplaceVanilla(hook.argument())) hook.value(true);
            }
            case "render.silent-aura-animation.end" -> Animations.endRender();
            case "optional.sodium.world-render.end" -> {
                if (hook.argument() == ChunkSectionLayerGroup.TRANSLUCENT) {
                    float partialTick =
                            Minecraft.getInstance()
                                    .getDeltaTracker()
                                    .getGameTimeDeltaPartialTick(true);
                    WorldRenderDispatch.post(new PoseStack(), partialTick);
                }
            }
            case "render.static-fov" -> {
                if (hook.value() instanceof Number fov) {
                    hook.value(StaticFov.apply(fov.floatValue()));
                }
            }
            case "render.chams-draw" -> {
                if (hook.owner() instanceof RenderType type
                        && hook.argument() instanceof MeshData mesh
                        && ModelOverlayRenderer.captureDraw(type, mesh)) hook.value(false);
            }
            case "render.chams-frame.begin" -> ModelOverlayRenderer.beginFrame();
            case "render.chams-frame.end" -> ModelOverlayRenderer.composite();
            case "render.chams-submit" -> {
                if (EventBus.LIVING_RENDER_PRE.listenerCount() != 0
                        && hook.argument() instanceof LivingEntityRenderState state) {
                    EventBus.LIVING_RENDER_PRE.post(new LivingRenderEvent.Pre(state));
                }
                if (hook.argument() instanceof AvatarRenderState state) {
                    Chams.beginPlayerChams(state);
                    ArmorHide.beginAvatar(state);
                } else {
                    ArmorHide.endAvatar();
                }
            }
            case "render.chams-submit.end" -> {
                ModelOverlayRenderer.endPlayer();
                ArmorHide.endAvatar();
                if (EventBus.LIVING_RENDER_POST.listenerCount() != 0
                        && hook.argument() instanceof LivingEntityRenderState state) {
                    EventBus.LIVING_RENDER_POST.post(new LivingRenderEvent.Post(state));
                }
            }
            case "render.armor-hide", "render.armor-hide.head", "render.armor-hide.wings" -> {
                if (!ArmorHide.shouldRenderCurrentArmor()) {
                    hook.value(false);
                }
            }
            case "render.armor-hide.head-item" -> {
                if (hook.argument() instanceof Object[] args
                        && args.length == 6
                        && args[0] instanceof ItemStackRenderState state
                        && args[1] instanceof ItemStack item
                        && args[2] instanceof ItemDisplayContext context
                        && args[4] instanceof LivingEntity wearer
                        && !ArmorHide.shouldRenderHeadItem(wearer, item, context)) {
                    // Reused render states can still contain the previously visible head item.
                    state.clear();
                    hook.value(false);
                }
            }
            case "render.chams-type" -> {
                if (hook.argument() instanceof AvatarRenderState state
                        && hook.value() instanceof RenderType type
                        && Chams.shouldRenderEntityChamsFor(state)) {
                    hook.value(ModelOverlayRenderer.remapIfNeeded(type));
                }
            }
            case "render.chams-equipment", "render.chams-cape" -> {
                if (hook.value() instanceof RenderType type) {
                    hook.value(ModelOverlayRenderer.remapIfNeeded(type));
                }
            }
            case "render.chams-mark-item" -> {
                Object submit =
                        hook.argument() != null ? hook.argument() : lastItemSubmit(hook.owner());
                if (submit != null) ModelOverlayRenderer.markHeldItemSubmit(submit);
            }
            case "render.chams-item" -> {
                if (hook.argument() != null)
                    ModelOverlayRenderer.beginRenderingItemSubmit(hook.argument());
            }
            case "render.chams-item.end" -> ModelOverlayRenderer.endRenderingItemSubmit();
            case "render.chams-item.type", "render.chams-foil.type" -> {
                if (hook.value() instanceof RenderType type) {
                    hook.value(ModelOverlayRenderer.remapHeldItemRenderType(type));
                }
            }
            case "render.player-nametag" -> {
                if (Nametags.isEnabled()) hook.value(false);
            }
            case "render.camera-zoom" -> {
                if (Clip.isEnabled()) hook.value(true);
            }
            case "render.full-bright" -> {
                if (hook.value() instanceof Number brightness) {
                    hook.value(FullBright.overrideBrightness(brightness.floatValue()));
                }
            }
            case "render.frustum-visible" -> frustumVisible(hook);
            case "render.clip-occlusion" -> {
                Minecraft client = Minecraft.getInstance();
                if (Clip.isEnabled()
                        && client.player != null
                        && !client.options.getCameraType().isFirstPerson()) {
                    hook.value(true);
                }
            }
            case "optional.sodium.clip-occlusion" -> {
                Minecraft client = Minecraft.getInstance();
                if (Clip.isEnabled()
                        && client.player != null
                        && !client.options.getCameraType().isFirstPerson()) {
                    hook.value(false);
                }
            }
            case "render.player-name" -> {
                if (hook.owner() instanceof Player player
                        && hook.value() instanceof Component component) {
                    Component named =
                            AntiNick.applyPlayerName(
                                    player, Nickname.replaceLocalPlayerName(player, component));
                    hook.value(PremiumCheckCommand.decorate(player, named));
                }
            }
            case "render.player-skin" -> {
                if (hook.owner() instanceof PlayerInfo info
                        && hook.value() instanceof PlayerSkin skin) {
                    hook.value(NicknameShuffle.skin(info, skin));
                }
            }
            case "render.tab-name" -> {
                if (hook.argument() instanceof PlayerInfo info
                        && hook.value() instanceof Component component) {
                    Component named =
                            AntiNick.applyTabName(info, Nickname.replaceTabName(info, component));
                    hook.value(PremiumCheckCommand.decorate(info, named));
                }
            }
            case "render.chat-system-name", "render.chat-player-name" -> {
                if (hook.value() instanceof Component component) {
                    hook.value(Nickname.replaceOwnNameInChat(component));
                }
            }
            case "render.chat-filter.player" ->
                    hook.value(!ChatFilter.shouldHideNativePlayerMessage());
            case "render.chat-filter.server-system" ->
                    hook.value(
                            !(hook.argument() instanceof Component component
                                    && ChatFilter.shouldHideServerSystemMessage(component)));

            case "xray.block-tessellate", "optional.sodium.render-model" -> xrayTessellate(hook);
            case "xray.block-tessellate.end", "optional.sodium.render-model.end" ->
                    XrayTerrain.endBackground();
            case "optional.sodium.process-quad.force-opaque" -> {
                if (XrayTerrain.supportsBackgroundTransparency()
                        && XrayTerrain.isEnabled()
                        && XrayTerrain.isRenderingBackground()) {
                    hook.value(false);
                }
            }
            case "optional.sodium.process-quad.layer" -> {
                if (hook.value() instanceof ChunkSectionLayer layer) {
                    hook.value(XrayTerrain.forceTranslucentLayer(layer));
                }
            }
            case "optional.sodium.process-quad.alpha" -> applySodiumAlpha(hook.argument());
            case "xray.force-opaque" -> {
                if (XrayTerrain.supportsBackgroundTransparency() && XrayTerrain.isEnabled()) {
                    hook.value(false);
                }
            }
            case "xray.quad-alpha" -> {
                if (hook.owner() instanceof ModelBlockRenderer renderer) {
                    XrayTerrain.applyAlpha(GameAccess.quadInstance(renderer));
                }
            }
            case "xray.material-layer" -> {
                if (hook.value() instanceof ChunkSectionLayer layer) {
                    hook.value(XrayTerrain.forceTranslucentLayer(layer));
                }
            }
            case "xray.section-quad.alpha" -> {
                if (hook.argument() instanceof QuadInstance instance) {
                    XrayTerrain.applyAlpha(instance);
                }
            }
            case "xray.section-quad.layer" -> {
                if (hook.value() instanceof ChunkSectionLayer layer) {
                    hook.value(XrayTerrain.forceTranslucentLayer(layer));
                }
            }
            case "xray.section-occlusion" -> {
                if (XrayTerrain.isEnabled()) hook.value(false);
            }

            case "command.client" -> {
                if (hook.argument() instanceof String command && ClientCommands.handle(command)) {
                    hook.value(false);
                }
            }
            default -> {}
        }
    }

    private static void livingAiStep(Object owner) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client.player;
        if (NoJumpDelay.isEnabled()
                && owner instanceof LivingEntity entity
                && currentPlayer == entity) {
            GameAccess.clearJumpDelay(entity);
        }
        if (owner == currentPlayer && currentPlayer != null) {
            EventBus.LOCAL_PLAYER_LIVING_TICK.post(new LocalPlayerLivingTickEvent(currentPlayer));
        }
    }

    private static void keyboardInput(Object owner) {
        if (!(owner instanceof KeyboardInput keyboard)) return;
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null) {
            publishMovementInputUpdated(keyboard);
            return;
        }

        Input filtered = Scaffold.filterMovementInput(client, keyboard.keyPresses);
        if (filtered != keyboard.keyPresses) {
            keyboard.keyPresses = filtered;
            GameAccess.moveVector(
                    (ClientInput) keyboard,
                    new Vec2(
                                    impulse(filtered.left(), filtered.right()),
                                    impulse(filtered.forward(), filtered.backward()))
                            .normalized());
        }
        MoveFix.State movementFix = MoveFix.capture(client);
        boolean correctMovement = movementFix.active();
        boolean suppressSprint = Scaffold.shouldSuppressSprint(client);
        if (!correctMovement && !suppressSprint) {
            publishMovementInputUpdated(keyboard);
            return;
        }

        Input original = keyboard.keyPresses;
        if (!correctMovement) {
            keyboard.keyPresses =
                    new Input(
                            original.forward(),
                            original.backward(),
                            original.left(),
                            original.right(),
                            original.jump(),
                            original.shift(),
                            false);
            publishMovementInputUpdated(keyboard);
            return;
        }

        Input corrected = MoveFix.correctInput(original, player.getYRot(), movementFix);
        int side = (int) impulse(corrected.left(), corrected.right());
        int front = (int) impulse(corrected.forward(), corrected.backward());
        keyboard.keyPresses =
                new Input(
                        front > 0,
                        front < 0,
                        side > 0,
                        side < 0,
                        original.jump(),
                        original.shift(),
                        original.sprint() && !suppressSprint);
        GameAccess.moveVector((ClientInput) keyboard, new Vec2(side, front).normalized());
        publishMovementInputUpdated(keyboard);
    }

    private static void publishMovementInputUpdated(KeyboardInput keyboard) {
        EventBus.MOVEMENT_INPUT_UPDATED.post(new MovementInputUpdatedEvent(keyboard));
    }

    private static void publishPickResult() {
        Minecraft client = Minecraft.getInstance();
        Reach.applyNormalPick(client);
        PickResultEvent event = new PickResultEvent(client, client.hitResult);
        EventBus.PICK_RESULT.post(event);
        client.hitResult = event.result();
    }

    private static void silentYaw(RuntimeEvents.MethodHook hook) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != hook.owner()) return;
        MoveFix.State movementFix = MoveFix.current(client);
        if (movementFix.active()) hook.value(movementFix.yaw());
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }

    private static Object lastItemSubmit(Object owner) {
        if (owner == null) return null;
        try {
            Object submits = owner.getClass().getMethod("getItemSubmits").invoke(owner);
            if (submits instanceof java.util.List<?> list && !list.isEmpty()) {
                return list.getLast();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static void frustumVisible(RuntimeEvents.MethodHook hook) {
        if (!Boolean.TRUE.equals(hook.value())
                && hook.argument() instanceof net.minecraft.world.phys.AABB box
                && Clip.forceVisible(box)) hook.value(true);
    }

    private static void xrayTessellate(RuntimeEvents.MethodHook hook) {
        if (!XrayTerrain.isEnabled()) return;
        if (!(hook.argument() instanceof Object[] arguments)
                || arguments.length != 3
                || !(arguments[1] instanceof BlockPos position)
                || !(arguments[2] instanceof BlockState state)) return;
        BlockAndTintGetter level =
                arguments[0] instanceof BlockAndTintGetter supplied
                        ? supplied
                        : Minecraft.getInstance().level;
        if (level == null) return;
        if (XrayTerrain.shouldSkipBlock(state, level, position)) {
            hook.value(false);
        } else {
            XrayTerrain.beginBackground();
        }
    }

    private static void applySodiumAlpha(Object quad) {
        if (!XrayTerrain.supportsBackgroundTransparency()
                || !XrayTerrain.isEnabled()
                || !XrayTerrain.isRenderingBackground()
                || quad == null) return;
        SodiumQuadAlpha.multiply(quad, 64.0F / 255.0F);
    }
}
