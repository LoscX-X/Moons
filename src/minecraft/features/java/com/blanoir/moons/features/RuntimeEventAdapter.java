package com.blanoir.moons.features;

import com.blanoir.moons.api.ResourceScope;
import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.action.AttackInputEvent;
import com.blanoir.moons.client.event.action.UseInputEvent;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.HudRenderEvent;
import com.blanoir.moons.client.event.frame.WorldRenderDispatch;
import com.blanoir.moons.client.event.input.KeyInputEvent;
import com.blanoir.moons.client.event.input.MouseButtonEvent;
import com.blanoir.moons.client.event.input.MouseMotionEvent;
import com.blanoir.moons.client.event.input.MouseScrollEvent;
import com.blanoir.moons.client.event.lifecycle.ClientContextChangedEvent;
import com.blanoir.moons.client.event.movement.MoveInputEvent;
import com.blanoir.moons.client.event.movement.PlayerMotionEvent;
import com.blanoir.moons.client.event.movement.PlayerMoveEndEvent;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.event.movement.StrafeEvent;
import com.blanoir.moons.client.event.network.PacketEventAdapter;
import com.blanoir.moons.client.event.render.EntityRenderStateEvent;
import com.blanoir.moons.client.event.render.RendererCloseEvent;
import com.blanoir.moons.client.event.tick.TickEndEvent;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.event.world.BlockUpdateEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.input.MouseInputTracker;
import com.blanoir.moons.client.management.rotation.MoveFix;
import com.blanoir.moons.client.management.rotation.RotationHistory;
import com.blanoir.moons.client.management.rotation.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationQuantizer;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.module.impl.combat.Reach;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.module.impl.combat.SprintReset;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.player.AntiLava;
import com.blanoir.moons.client.module.impl.player.AntiWeb;
import com.blanoir.moons.client.module.impl.player.AutoLava;
import com.blanoir.moons.client.module.impl.player.AutoSword;
import com.blanoir.moons.client.module.impl.player.AutoWeb;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.module.impl.render.xray.OreScanner;
import com.blanoir.moons.client.module.impl.world.AutoTool;
import com.blanoir.moons.client.module.impl.world.Scaffold;
import com.blanoir.moons.client.ui.clickgui.ModuleGui;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.time.FrameClock;
import com.blanoir.moons.runtime.RuntimeEvents;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

/** Converts loader-neutral runtime events to the existing typed feature API. */
final class RuntimeEventAdapter {
    private static final double MIN_FRAME_SECONDS = 1.0D / 240.0D;

    private final RuntimeEvents runtime;
    private final FrameClock frameClock = new FrameClock(MIN_FRAME_SECONDS);
    private final Map<Object, MouseCapture> mouseCaptures = weakMap();
    private final Map<Object, PositionCapture> positionCaptures = weakMap();
    private final Map<Object, ActionCapture> actionCaptures = weakMap();
    private final RenderRotationController renderRotations = new RenderRotationController();
    private final ThreadLocal<Deque<BlockState>> previousBlocks =
            ThreadLocal.withInitial(ArrayDeque::new);
    private ClientContextChangedEvent.Snapshot clientContext;
    private volatile DeltaTracker deltaTracker;

    RuntimeEventAdapter(RuntimeEvents runtime) {
        this.runtime = runtime;
    }

    void bind(ResourceScope resources) {
        // Registration order is observable for cancellation and capture state.
        resources.own(runtime.clientTick().subscribe(this::tick));
        resources.own(runtime.frame().subscribe(this::frame));
        resources.own(runtime.hud().subscribe(this::hud));
        resources.own(runtime.worldRender().subscribe(this::worldRender));
        resources.own(runtime.key().subscribe(this::key));
        resources.own(runtime.mouse().subscribe(this::mouse));
        resources.own(runtime.mouseScroll().subscribe(this::mouseScroll));
        resources.own(runtime.mouseMove().subscribe(this::mouseMove));
        resources.own(runtime.action().subscribe(this::action));
        resources.own(runtime.packet().subscribe(this::packet));
        resources.own(runtime.blockUpdate().subscribe(this::blockUpdate));
        resources.own(runtime.playerUpdate().subscribe(this::playerUpdate));
        resources.own(runtime.moveInput().subscribe(this::moveInput));
        resources.own(runtime.playerMove().subscribe(this::playerMove));
        resources.own(runtime.playerMoveEnd().subscribe(this::playerMoveEnd));
        resources.own(runtime.playerPosition().subscribe(this::playerPosition));
        resources.own(runtime.renderState().subscribe(this::renderState));
        resources.own(runtime.rendererClose().subscribe(this::rendererClose));
        resources.own(runtime.methodHook().subscribe(FeatureHooks::apply));
    }

    private void tick(RuntimeEvents.ClientTick event) {
        Minecraft client = (Minecraft) event.minecraft();
        if (event.phase() == RuntimeEvents.Phase.START) {
            publishContextChange(client);
            EventBus.TICK.post(new TickEvent(client));
        } else {
            EventBus.TICK_END.post(new TickEndEvent(client));
        }
    }

    private void publishContextChange(Minecraft client) {
        ClientContextChangedEvent.Snapshot current = contextOf(client);
        if (clientContext == null) {
            clientContext = current;
            return;
        }
        if (sameContext(clientContext, current)) return;
        ClientContextChangedEvent.Snapshot previous = clientContext;
        clientContext = current;
        EventBus.CLIENT_CONTEXT_CHANGED.post(
                new ClientContextChangedEvent(client, previous, current));
    }

    private static ClientContextChangedEvent.Snapshot contextOf(Minecraft client) {
        return new ClientContextChangedEvent.Snapshot(
                client == null ? null : client.level,
                client == null ? null : client.player,
                client == null ? null : client.getConnection());
    }

    private static boolean sameContext(
            ClientContextChangedEvent.Snapshot first, ClientContextChangedEvent.Snapshot second) {
        return first.level() == second.level()
                && first.player() == second.player()
                && first.connection() == second.connection();
    }

    private void frame(RuntimeEvents.Frame event) {
        WorldRenderDispatch.beginFrame();
        if (event.deltaTracker() instanceof DeltaTracker tracker) deltaTracker = tracker;
        double deltaSeconds = Math.max(MIN_FRAME_SECONDS, frameClock.nextDeltaSeconds());
        EventBus.FRAME.post(new FrameEvent(Minecraft.getInstance(), deltaSeconds));
    }

    private void hud(RuntimeEvents.Hud event) {
        if (event.extractor() instanceof GuiGraphicsExtractor graphics
                && event.deltaTracker() instanceof DeltaTracker tracker) {
            EventBus.HUD_RENDER.post(new HudRenderEvent(graphics, tracker));
        }
    }

    private void worldRender(RuntimeEvents.WorldRender event) {
        if (!(event.poseStack() instanceof PoseStack poseStack)) return;
        if (event.stage() == 0) {
            if (event.renderer() instanceof LevelRenderer renderer
                    && event.levelRenderState() instanceof LevelRenderState levelRenderState) {
                SubmitNodeCollector collector = GameAccess.submitNodeCollector(renderer);
                Backtrack.renderModel(poseStack, levelRenderState, collector);
            }
            return;
        }
        DeltaTracker tracker = deltaTracker;
        float partialTick = tracker == null ? 1.0F : tracker.getGameTimeDeltaPartialTick(true);
        WorldRenderDispatch.post(poseStack, partialTick);
    }

    private void key(RuntimeEvents.Key event) {
        if (event.handler() instanceof KeyboardHandler handler
                && event.event() instanceof KeyEvent keyEvent) {
            KeyInputEvent input =
                    new KeyInputEvent(handler, event.window(), event.action(), keyEvent);
            EventBus.KEY_INPUT.post(input);
            if (input.isCancelled()) {
                event.control().cancel();
                return;
            }
        }
        if (event.action() != GLFW.GLFW_PRESS || !(event.event() instanceof KeyEvent keyEvent))
            return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        if (MinecraftClientAccess.screen(client) == null) {
            for (int slot = 0; slot < client.options.keyHotbarSlots.length; slot++) {
                if (client.options.keyHotbarSlots[slot].matches(keyEvent)
                        && Scaffold.handleHotbarSwap(slot, 0)) {
                    event.control().cancel();
                    return;
                }
            }
        }
        routeBindingPress(
                client,
                ModuleKeybinds.fromEvent(keyEvent),
                MinecraftClientAccess.screen(client) == null,
                event.control());
    }

    private void mouse(RuntimeEvents.Mouse event) {
        if (event.handler() instanceof MouseHandler handler
                && event.button() instanceof MouseButtonInfo button) {
            MouseButtonEvent input =
                    new MouseButtonEvent(handler, event.window(), button, event.action());
            EventBus.MOUSE_BUTTON.post(input);
            if (input.isCancelled()) {
                event.control().cancel();
                return;
            }
        }
        if (event.action() != GLFW.GLFW_PRESS
                || !(event.button() instanceof MouseButtonInfo button)) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        routeBindingPress(
                client,
                ModuleKeybinds.fromMouseButton(button.button()),
                MinecraftClientAccess.screen(client) == null,
                event.control());
    }

    private static void routeBindingPress(
            Minecraft client,
            InputConstants.Key key,
            boolean allowGameplayBindings,
            RuntimeEvents.Control control) {
        ModuleKeybinds.Dispatch dispatch = ModuleKeybinds.dispatchPress(key, allowGameplayBindings);
        if (dispatch == ModuleKeybinds.Dispatch.GUI) {
            ModuleGui.toggle(client);
        }
        if (dispatch.consumed()) {
            control.cancel();
        }
    }

    private void mouseScroll(RuntimeEvents.MouseScroll event) {
        if (event.handler() instanceof MouseHandler handler) {
            MouseScrollEvent input =
                    new MouseScrollEvent(handler, event.window(), event.xOffset(), event.yOffset());
            EventBus.MOUSE_SCROLL.post(input);
            if (input.isCancelled()) {
                event.control().cancel();
                return;
            }
        }
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client.player;
        if (currentPlayer == null
                || client.level == null
                || currentPlayer.isSpectator()
                || MinecraftClientAccess.screen(client) != null
                || event.yOffset() == 0.0D) return;
        int offset = event.yOffset() > 0.0D ? 1 : -1;
        if (Scaffold.handleHotbarSwap(-1, offset)) event.control().cancel();
    }

    private void mouseMove(RuntimeEvents.MouseMove event) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (event.phase() == RuntimeEvents.Phase.START) {
            if (event.handler() instanceof MouseHandler handler) {
                EventBus.MOUSE_MOTION_PRE.post(new MouseMotionEvent.Pre(handler));
            }
            if (player != null) {
                mouseCaptures.put(
                        event.handler(), new MouseCapture(player.getYRot(), player.getXRot()));
            }
            return;
        }
        MouseCapture capture = mouseCaptures.remove(event.handler());
        if (capture != null && player != null) {
            MouseInputTracker.recordFrame(
                    System.nanoTime(),
                    Mth.wrapDegrees(player.getYRot() - capture.yaw),
                    player.getXRot() - capture.pitch);
        }
        if (event.handler() instanceof MouseHandler handler) {
            EventBus.MOUSE_MOTION_POST.post(new MouseMotionEvent.Post(handler));
        }
    }

    private void action(RuntimeEvents.Action event) {
        if (!(event.minecraft() instanceof Minecraft client)) return;
        var player = client.player;
        if (event.phase() == RuntimeEvents.Phase.START) {
            Rotation manualRotation = RotationLease.manualRotation();
            if (event.kind() == RuntimeEvents.Kind.USE
                    && manualRotation != null
                    && player != null
                    && !RotationHistory.same(
                            manualRotation, new Rotation(player.getYRot(), player.getXRot()))) {
                event.control().cancel();
                return;
            }
            if (publishActionPre(client, event.kind())) {
                event.control().cancel();
                return;
            }
            if (event.kind() == RuntimeEvents.Kind.USE
                    && (Scaffold.cancelUseAction() || SilentAura.shouldSuppressUseAction(client))) {
                event.control().cancel();
                return;
            }
            if (event.kind() == RuntimeEvents.Kind.ATTACK && Reach.handleManualAttack(client)) {
                event.control().cancel();
                return;
            }
            beginAction(client, event.kind());
        } else {
            endAction(client, event.kind());
            publishActionPost(client, event.kind());
        }
    }

    private static boolean publishActionPre(Minecraft client, RuntimeEvents.Kind kind) {
        if (kind == RuntimeEvents.Kind.ATTACK) {
            AttackInputEvent.Pre event = new AttackInputEvent.Pre(client);
            EventBus.ATTACK_INPUT_PRE.post(event);
            return event.isCancelled();
        }
        UseInputEvent.Pre event = new UseInputEvent.Pre(client);
        EventBus.USE_INPUT_PRE.post(event);
        return event.isCancelled();
    }

    private static void publishActionPost(Minecraft client, RuntimeEvents.Kind kind) {
        if (kind == RuntimeEvents.Kind.ATTACK) {
            EventBus.ATTACK_INPUT_POST.post(new AttackInputEvent.Post(client));
        } else {
            EventBus.USE_INPUT_POST.post(new UseInputEvent.Post(client));
        }
    }

    private void beginAction(Minecraft client, RuntimeEvents.Kind kind) {
        ActionCapture capture =
                actionCaptures.computeIfAbsent(client, ignored -> new ActionCapture());
        if (kind == RuntimeEvents.Kind.ATTACK) {
            capture.attackTarget = null;
            capture.autoLavaCriticalEligible = false;
            Animations.onAttack();
            EntityHitResult replacement = CombatInputController.consumePendingAttackHit(client);
            if (replacement != null) {
                capture.attackHit = client.hitResult;
                capture.attackActive = true;
                client.hitResult = replacement;
            }
            if (client.hitResult instanceof EntityHitResult hit) {
                Entity target = hit.getEntity();
                SprintReset.onAttack(target);
                AutoTool.onAttack(target);
                AutoSword.onAttack(target);
                // Auto placement is committed from the END hook, after vanilla
                // has dispatched ATTACK. Preserve the pre-hit critical sample:
                // attack strength is reset synchronously by the packet path.
                capture.attackTarget = target;
                capture.autoLavaCriticalEligible = AutoLava.isCriticalTriggerEligible(target);
                Critical.beforeAttack(client, target);
            }
        } else if (SilentPacketRotation.beginSimulatedUse(client)) {
            LocalPlayer player = client.player;
            capture.useActive = true;
            capture.usePlayer = player;
            capture.useHit = client.hitResult;
            if (player != null) {
                capture.useYaw = player.getYRot();
                capture.usePitch = player.getXRot();
                player.setYRot(SilentPacketRotation.getSimulatedUseYaw());
                player.setXRot(SilentPacketRotation.getSimulatedUsePitch());
            }
            client.hitResult = SilentPacketRotation.getSimulatedUseHit();
        }
    }

    private void endAction(Minecraft client, RuntimeEvents.Kind kind) {
        ActionCapture capture = actionCaptures.get(client);
        if (capture == null) return;
        if (kind == RuntimeEvents.Kind.ATTACK) {
            Entity attacked = capture.attackTarget;
            boolean autoLavaCriticalEligible = capture.autoLavaCriticalEligible;
            if (capture.attackActive) {
                client.hitResult = capture.attackHit;
            }
            capture.attackActive = false;
            capture.attackHit = null;
            capture.attackTarget = null;
            capture.autoLavaCriticalEligible = false;
            if (attacked != null) {
                AutoLava.onAttackDispatched(attacked, autoLavaCriticalEligible);
                AutoWeb.onAttack(attacked);
            }
        } else if (kind == RuntimeEvents.Kind.USE && capture.useActive) {
            client.hitResult = capture.useHit;
            if (capture.usePlayer != null) {
                capture.usePlayer.setYRot(capture.useYaw);
                capture.usePlayer.setXRot(capture.usePitch);
            }
            capture.useActive = false;
            capture.usePlayer = null;
            capture.useHit = null;
            SilentPacketRotation.finishSimulatedUse();
        }
    }

    private void packet(RuntimeEvents.Packet event) {
        switch (event.phase()) {
            case SEND_PRE -> {
                if (!PacketEventAdapter.send(event.connection(), event.packet()))
                    event.control().cancel();
            }
            case SEND_POST -> PacketEventAdapter.sent(event.connection(), event.packet());
            case RECEIVE_NETWORK -> {
                PacketEventAdapter.receive(
                        event.packet(), event.connection(), event.control()::cancel);
            }
            case RECEIVE_APPLY -> PacketEventAdapter.apply(event.packet(), event.connection());
        }
    }

    private void blockUpdate(RuntimeEvents.BlockUpdate event) {
        if (!(event.level() instanceof ClientLevel level)
                || !(event.position() instanceof BlockPos position)
                || !(event.newState() instanceof BlockState state)) return;
        Deque<BlockState> stack = previousBlocks.get();
        if (event.phase() == RuntimeEvents.Phase.START) {
            BlockState previous = level.getBlockState(position);
            stack.push(previous);
            EventBus.BLOCK_UPDATE_PRE.post(
                    new BlockUpdateEvent.Pre(level, position, previous, state));
            return;
        }
        BlockState previous = stack.isEmpty() ? state : stack.pop();
        if (event.applied()) {
            AntiLava.onBlockUpdate(position, state);
            AntiWeb.onBlockUpdate(position, previous, state);
        }
        OreScanner.queueBlockUpdate(position);
        EventBus.BLOCK_UPDATE_POST.post(
                new BlockUpdateEvent.Post(level, position, previous, state, event.applied()));
        if (stack.isEmpty()) previousBlocks.remove();
    }

    private void moveInput(RuntimeEvents.MoveInput event) {
        if (event.player() instanceof LocalPlayer player
                && Minecraft.getInstance().player == player) {
            EventBus.MOVE_INPUT.post(new MoveInputEvent());
        }
    }

    private void playerUpdate(RuntimeEvents.PlayerUpdate event) {
        if (event.player() instanceof LocalPlayer player
                && Minecraft.getInstance().player == player) {
            PlayerUpdateEvent updateEvent = new PlayerUpdateEvent(Minecraft.getInstance());
            // A previous cancelled player update may never have reached sendPosition.
            // Interaction pins survive this ordinary submission cleanup.
            RotationLease.finishMotion();
            EventBus.PLAYER_UPDATE.post(updateEvent);
            if (updateEvent.isCancelled()) event.control().cancel();
        }
    }

    private void playerMove(RuntimeEvents.PlayerMove event) {
        if (!(event.player() instanceof Entity entity) || Minecraft.getInstance().player != entity)
            return;
        Minecraft client = Minecraft.getInstance();
        if (event.movement() instanceof Vec3 movement) {
            float initialStrafe = (float) movement.x;
            float initialForward = (float) movement.z;
            float initialFriction = event.scale();
            StrafeEvent strafe = new StrafeEvent(initialStrafe, initialForward, initialFriction);
            EventBus.STRAFE.post(strafe);
            // Preserve vanilla's original double vector unless a listener
            // actually changes it. Rebuilding it from floats every move was
            // an old Strafe side effect and introduced needless prediction
            // drift even in the Vanilla Telly/Tower path.
            if (Float.compare(strafe.getStrafe(), initialStrafe) != 0
                    || Float.compare(strafe.getForward(), initialForward) != 0) {
                event.movement(new Vec3(strafe.getStrafe(), movement.y, strafe.getForward()));
            }
            if (Float.compare(strafe.getFriction(), initialFriction) != 0) {
                event.scale(strafe.getFriction());
            }
        }
        MoveFix.State movementFix = MoveFix.current(client);
        if (!movementFix.active()) return;
        float yaw = movementFix.yaw();
        PositionCapture capture =
                positionCaptures.computeIfAbsent(entity, ignored -> new PositionCapture());
        capture.movementYaw = entity.getYRot();
        capture.movementActive = true;
        entity.setYRot(yaw);
    }

    private void playerMoveEnd(RuntimeEvents.PlayerMoveEnd event) {
        restoreMovementYaw();
        if (event.player() instanceof LocalPlayer player
                && Minecraft.getInstance().player == player) {
            EventBus.PLAYER_MOVE_END.post(new PlayerMoveEndEvent(player));
        }
    }

    private void playerPosition(RuntimeEvents.PlayerPosition event) {
        if (!(event.player() instanceof LocalPlayer player)) return;
        PositionCapture capture =
                positionCaptures.computeIfAbsent(player, ignored -> new PositionCapture());
        if (event.phase() == RuntimeEvents.Phase.START) {
            capture.eventCameraYaw = player.getYRot();
            capture.eventCameraPitch = player.getXRot();
            RotationLease.beginMotion();
            applyPacketRotation(player, capture);
            capture.eventOutgoingYaw = player.getYRot();
            capture.eventOutgoingPitch = player.getXRot();
            capture.eventOverridden =
                    Float.compare(capture.eventCameraYaw, capture.eventOutgoingYaw) != 0
                            || Float.compare(capture.eventCameraPitch, capture.eventOutgoingPitch)
                                    != 0;
            EventBus.PLAYER_MOTION_PRE.post(
                    new PlayerMotionEvent.Pre(
                            player,
                            capture.eventCameraYaw,
                            capture.eventCameraPitch,
                            capture.eventOutgoingYaw,
                            capture.eventOutgoingPitch,
                            capture.eventOverridden));
        } else {
            PlayerMotionEvent.Post motion =
                    new PlayerMotionEvent.Post(
                            player,
                            capture.eventCameraYaw,
                            capture.eventCameraPitch,
                            capture.eventOutgoingYaw,
                            capture.eventOutgoingPitch,
                            capture.eventOverridden);
            restorePacketRotation(player, capture);
            EventBus.PLAYER_MOTION_POST.post(motion);
            // POST listeners may release an owner or enqueue the next phase.
            // Prepare it now, after all observers saw the closing movement.
            RotationLease.resumePending();
        }
    }

    private void applyPacketRotation(LocalPlayer player, PositionCapture state) {
        Rotation rotation;
        RotationLease.Submission committed = RotationLease.submission();
        if (committed != null) {
            rotation = committed.rotation();
        } else if (SilentAura.shouldApplyManualUseRotation()) {
            rotation = new Rotation(SilentAura.getManualUseYaw(), SilentAura.getManualUsePitch());
        } else if (SilentPacketRotation.shouldApplyRotation()) {
            rotation = SilentPacketRotation.packetRotation(Minecraft.getInstance());
        } else if (Scaffold.shouldApplyRotation()) {
            rotation = Scaffold.getPacketRotation();
        } else if (SilentAura.shouldApplyRotation()) {
            rotation = new Rotation(SilentAura.getPacketYaw(), SilentAura.getPacketPitch());
        } else {
            if (!state.continuous) return;
            Rotation base = RotationHistory.start(Minecraft.getInstance());
            // Keep the camera itself in the same whole-turn domain before it is
            // captured for restoration. Correcting just this closing packet
            // would let the next vanilla packet jump from 181 back to -179.
            float cameraYaw = player.getYRot();
            float continuousYaw = RotationQuantizer.continuousYaw(base.yaw(), cameraYaw);
            player.setYRot(continuousYaw);
            player.yRotO += continuousYaw - cameraYaw;
            rotation =
                    new Rotation(
                            SilentPacketRotation.quantizePacketYaw(base.yaw(), player.getYRot()),
                            SilentPacketRotation.quantizePacketPitch(
                                    base.pitch(), player.getXRot()));
            state.continuous = false;
            applyTemporaryRotation(player, state, rotation);
            return;
        }
        state.continuous = true;
        applyTemporaryRotation(player, state, rotation);
    }

    private void applyTemporaryRotation(
            LocalPlayer player, PositionCapture state, Rotation rotation) {
        state.cameraYaw = player.getYRot();
        state.cameraPitch = player.getXRot();
        state.packetActive = true;
        player.setYRot(rotation.yaw());
        player.setXRot(rotation.pitch());
    }

    private void restorePacketRotation(LocalPlayer player, PositionCapture state) {
        if (state.packetActive) {
            player.setYRot(state.cameraYaw);
            player.setXRot(state.cameraPitch);
            state.packetActive = false;
        }
        // A completed method may have emitted no packet, or had its send cancelled.
        RotationLease.finishMotion();
    }

    private void renderState(RuntimeEvents.RenderState event) {
        if (event.entity() instanceof Entity entity
                && event.state() instanceof EntityRenderState state) {
            Reach.applyAdvancedRender(entity, state);
        }
        if (event.entity() instanceof Avatar avatar
                && event.state() instanceof AvatarRenderState state) {
            renderRotations.apply(avatar, state, event.partialTick());
            Animations.applyThirdPerson(avatar, state);
        }
        if (event.entity() instanceof Entity entity
                && event.state() instanceof EntityRenderState state) {
            EventBus.ENTITY_RENDER_STATE.post(
                    new EntityRenderStateEvent(entity, state, event.partialTick()));
        }
    }

    private void rendererClose(RuntimeEvents.RendererClose event) {
        if (event.renderer() instanceof GameRenderer renderer) {
            EventBus.RENDERER_CLOSE.post(new RendererCloseEvent(renderer));
        }
    }

    private void restoreMovementYaw() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;
        PositionCapture capture = positionCaptures.get(player);
        if (capture != null && capture.movementActive) {
            player.setYRot(capture.movementYaw);
            capture.movementActive = false;
        }
    }

    private static <K, V> Map<K, V> weakMap() {
        return Collections.synchronizedMap(new WeakHashMap<>());
    }

    private record MouseCapture(float yaw, float pitch) {}

    private static final class ActionCapture {
        boolean attackActive;
        HitResult attackHit;
        Entity attackTarget;
        boolean autoLavaCriticalEligible;
        boolean useActive;
        LocalPlayer usePlayer;
        HitResult useHit;
        float useYaw;
        float usePitch;
    }

    private static final class PositionCapture {
        boolean movementActive;
        float movementYaw;
        boolean packetActive;
        boolean continuous;
        float cameraYaw;
        float cameraPitch;
        float eventCameraYaw;
        float eventCameraPitch;
        float eventOutgoingYaw;
        float eventOutgoingPitch;
        boolean eventOverridden;
    }
}
