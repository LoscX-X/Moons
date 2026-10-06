package com.blanoir.moons.client.event;

import com.blanoir.moons.api.ResourceScope;
import com.blanoir.moons.client.compat.render.EntityRenderState;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.action.AttackInputEvent;
import com.blanoir.moons.client.event.action.UseInputEvent;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.HudRenderEvent;
import com.blanoir.moons.client.event.frame.WorldRenderDispatch;
import com.blanoir.moons.client.event.lifecycle.ClientContextChangedEvent;
import com.blanoir.moons.client.event.movement.MoveInputEvent;
import com.blanoir.moons.client.event.movement.PlayerMotionEvent;
import com.blanoir.moons.client.event.movement.PlayerMoveEndEvent;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.event.network.PacketEventAdapter;
import com.blanoir.moons.client.event.render.EntityRenderStateEvent;
import com.blanoir.moons.client.event.render.RendererCloseEvent;
import com.blanoir.moons.client.event.tick.TickEndEvent;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.event.world.BlockUpdateEvent;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.manager.lease.RotationLease;
import com.blanoir.moons.client.manager.rotation.MoveFix;
import com.blanoir.moons.client.manager.rotation.RotationManager;
import com.blanoir.moons.client.manager.rotation.RotationQuantizer;
import com.blanoir.moons.client.manager.rotation.SilentPacketRotation;
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
import com.blanoir.moons.client.module.impl.world.FastPlace;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.render.LegacyPoseStack;
import com.blanoir.moons.client.render.RenderRotationController;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.time.FrameClock;
import com.blanoir.moons.runtime.RuntimeEvents;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

/** Converts loader-neutral runtime events to the existing typed feature API. */
public final class RuntimeEventAdapter {
    private static final double INITIAL_FRAME_SECONDS = 1.0D / 240.0D;

    private final RuntimeEvents runtime;
    private final com.blanoir.moons.client.lifecycle.ActivationGate activation;
    private final FrameClock frameClock = new FrameClock(INITIAL_FRAME_SECONDS);
    private final Map<Object, PositionCapture> positionCaptures = weakMap();
    private final Map<Object, ActionCapture> actionCaptures = weakMap();
    private final RenderRotationController renderRotations = new RenderRotationController();
    private final ThreadLocal<Deque<IBlockState>> previousBlocks =
            ThreadLocal.withInitial(ArrayDeque::new);
    private ClientContextChangedEvent.Snapshot clientContext;
    private volatile float partialTick = 1.0F;
    private final com.blanoir.moons.client.input.InputEventDispatcher inputs =
            new com.blanoir.moons.client.input.InputEventDispatcher();

    public RuntimeEventAdapter(
            RuntimeEvents runtime, com.blanoir.moons.client.lifecycle.ActivationGate activation) {
        this.activation = activation;
        this.runtime = runtime;
    }

    public void bind(ResourceScope resources) {
        resources.own(inputs);
        // Registration order is observable for cancellation and capture state.
        resources.own(runtime.clientTick().subscribe(activation.guard(this::tick)));
        resources.own(runtime.frame().subscribe(activation.guard(this::frame)));
        resources.own(runtime.hud().subscribe(activation.guard(this::hud)));
        resources.own(runtime.worldRender().subscribe(activation.guard(this::worldRender)));
        resources.own(runtime.key().subscribe(activation.guard(inputs::key)));
        resources.own(runtime.mouse().subscribe(activation.guard(inputs::mouse)));
        resources.own(runtime.mouseScroll().subscribe(activation.guard(inputs::mouseScroll)));
        resources.own(runtime.mouseMove().subscribe(activation.guard(inputs::mouseMove)));
        resources.own(runtime.action().subscribe(activation.guard(this::action)));
        resources.own(runtime.packet().subscribe(activation.guard(this::packet)));
        resources.own(runtime.blockUpdate().subscribe(activation.guard(this::blockUpdate)));
        resources.own(runtime.playerUpdate().subscribe(activation.guard(this::playerUpdate)));
        resources.own(runtime.moveInput().subscribe(activation.guard(this::moveInput)));
        resources.own(runtime.playerMove().subscribe(activation.guard(this::playerMove)));
        resources.own(runtime.playerMoveEnd().subscribe(activation.guard(this::playerMoveEnd)));
        resources.own(runtime.playerPosition().subscribe(activation.guard(this::playerPosition)));
        resources.own(runtime.renderState().subscribe(activation.guard(this::renderState)));
        resources.own(runtime.rendererClose().subscribe(activation.guard(this::rendererClose)));
        resources.own(
                runtime.methodHook()
                        .subscribe(
                                activation.guardFilter(ClientEventDispatcher::isActive),
                                activation.guard(ClientEventDispatcher::apply)));
    }

    public void resetInputs() {
        inputs.reset();
    }

    private void tick(RuntimeEvents.ClientTick event) {
        Minecraft client = (Minecraft) event.minecraft();
        if (event.phase() == RuntimeEvents.Phase.START) {
            publishContextChange(client);
            inputs.poll();
            EventBus.TICK.post(new TickEvent(client));
        } else {
            EventBus.TICK_END.post(new TickEndEvent(client));
            Settings.flushPending();
        }
    }

    private void publishContextChange(Minecraft client) {
        ClientContextChangedEvent.Snapshot current = contextOf(client);
        if (clientContext == null) {
            clientContext = current;
            return;
        }
        if (sameContext(clientContext, current)) return;
        inputs.reset();
        ClientContextChangedEvent.Snapshot previous = clientContext;
        clientContext = current;
        EventBus.CLIENT_CONTEXT_CHANGED.post(
                new ClientContextChangedEvent(client, previous, current));
    }

    private static ClientContextChangedEvent.Snapshot contextOf(Minecraft client) {
        return new ClientContextChangedEvent.Snapshot(
                client == null ? null : client.theWorld,
                client == null ? null : client.thePlayer,
                client == null ? null : client.getNetHandler());
    }

    private static boolean sameContext(
            ClientContextChangedEvent.Snapshot first, ClientContextChangedEvent.Snapshot second) {
        return first.level() == second.level()
                && first.player() == second.player()
                && first.connection() == second.connection();
    }

    private void frame(RuntimeEvents.Frame event) {
        inputs.poll();
        WorldRenderDispatch.beginFrame();
        if (event.deltaTracker() instanceof Number tracker) partialTick = tracker.floatValue();
        double deltaSeconds = frameClock.nextDeltaSeconds();
        EventBus.FRAME.post(new FrameEvent(Minecraft.getMinecraft(), deltaSeconds));
        Settings.flushPending();
    }

    private void hud(RuntimeEvents.Hud event) {
        float delta =
                event.deltaTracker() instanceof Number number ? number.floatValue() : partialTick;
        EventBus.HUD_RENDER.post(
                new HudRenderEvent(
                        new com.blanoir.moons.client.ui.render.LegacyGuiGraphics(
                                Minecraft.getMinecraft()),
                        delta));
    }

    private void worldRender(RuntimeEvents.WorldRender event) {
        float delta =
                event.levelRenderState() instanceof Number number
                        ? number.floatValue()
                        : partialTick;
        // 1.8's world pass already has its camera/model-view matrices active.
        // Overlay geometry composes its own stack with those live GL matrices.
        LegacyPoseStack pose =
                event.poseStack() instanceof LegacyPoseStack supplied
                        ? supplied
                        : new LegacyPoseStack();
        if (event.stage() == 0) {
            Backtrack.renderModel(delta);
            return;
        }
        Backtrack.renderModel(delta);
        WorldRenderDispatch.post(pose, delta);
    }

    private void action(RuntimeEvents.Action event) {
        if (!(event.minecraft() instanceof Minecraft client)) return;
        var player = client.thePlayer;
        if (event.phase() == RuntimeEvents.Phase.START) {
            boolean automated =
                    event.kind() == RuntimeEvents.Kind.ATTACK
                            ? CombatInputController.isInvokingTargetAttack()
                            : SilentPacketRotation.isInvokingSimulatedUse();
            if (!automated && RotationLease.hasSilentRotation()) {
                event.control().cancel();
                return;
            }
            Rotation manualRotation = RotationLease.manualRotation();
            if (event.kind() == RuntimeEvents.Kind.USE
                    && manualRotation != null
                    && player != null
                    && !RotationManager.same(
                            manualRotation,
                            new Rotation(player.rotationYaw, player.rotationPitch))) {
                event.control().cancel();
                return;
            }
            if (publishActionPre(client, event.kind())) {
                event.control().cancel();
                return;
            }
            if (event.kind() == RuntimeEvents.Kind.USE
                    && !automated
                    && (Scaffold.cancelUseAction() || SilentAura.shouldSuppressUseAction(client))) {
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
            MovingObjectPosition replacement =
                    CombatInputController.consumePendingAttackHit(client);
            if (replacement != null) {
                capture.attackHit = client.objectMouseOver;
                capture.attackActive = true;
                client.objectMouseOver = replacement;
            }
            if (client.objectMouseOver != null
                    && client.objectMouseOver.typeOfHit
                            == MovingObjectPosition.MovingObjectType.ENTITY) {
                Entity target = client.objectMouseOver.entityHit;
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
            EntityPlayerSP player = client.thePlayer;
            capture.useActive = true;
            capture.usePlayer = player;
            capture.useHit = client.objectMouseOver;
            if (player != null) {
                capture.useYaw = player.rotationYaw;
                capture.usePitch = player.rotationPitch;
                player.rotationYaw = SilentPacketRotation.getSimulatedUseYaw();
                player.rotationPitch = SilentPacketRotation.getSimulatedUsePitch();
            }
            client.objectMouseOver = SilentPacketRotation.getSimulatedUseHit();
        } else {
            var hit = FastPlace.placementHit(client);
            if (hit != null) {
                capture.manualUseActive = true;
                capture.manualUseHit = client.objectMouseOver;
                client.objectMouseOver = hit;
            }
        }
    }

    private void endAction(Minecraft client, RuntimeEvents.Kind kind) {
        ActionCapture capture = actionCaptures.get(client);
        if (capture == null) return;
        if (kind == RuntimeEvents.Kind.ATTACK) {
            Entity attacked = capture.attackTarget;
            boolean autoLavaCriticalEligible = capture.autoLavaCriticalEligible;
            if (capture.attackActive) {
                client.objectMouseOver = capture.attackHit;
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
            client.objectMouseOver = capture.useHit;
            if (capture.usePlayer != null) {
                capture.usePlayer.rotationYaw = capture.useYaw;
                capture.usePlayer.rotationPitch = capture.usePitch;
            }
            capture.useActive = false;
            capture.usePlayer = null;
            capture.useHit = null;
            SilentPacketRotation.finishSimulatedUse();
        } else if (kind == RuntimeEvents.Kind.USE && capture.manualUseActive) {
            client.objectMouseOver = capture.manualUseHit;
            capture.manualUseHit = null;
            capture.manualUseActive = false;
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
        if (!(event.level() instanceof WorldClient level)
                || !(event.position() instanceof BlockPos position)
                || !(event.newState() instanceof IBlockState state)) return;
        Deque<IBlockState> stack = previousBlocks.get();
        if (event.phase() == RuntimeEvents.Phase.START) {
            IBlockState previous = level.getBlockState(position);
            stack.push(previous);
            EventBus.BLOCK_UPDATE_PRE.post(
                    new BlockUpdateEvent.Pre(level, position, previous, state));
            return;
        }
        IBlockState previous = stack.isEmpty() ? state : stack.pop();
        if (event.applied()) {
            AntiLava.onBlockUpdate(position, state);
            AntiWeb.onBlockUpdate(position, previous, state);
        }
        OreScanner.queueBlockUpdate(position);
        EventBus.BLOCK_UPDATE_POST.post(
                new BlockUpdateEvent.Post(level, position, previous, state, event.applied()));
        // Keep the empty deque for the next block update on this thread.
        // Removing it here recreated both the deque and ThreadLocal entry per block.
    }

    private void moveInput(RuntimeEvents.MoveInput event) {
        if (event.player() instanceof EntityPlayerSP player
                && Minecraft.getMinecraft().thePlayer == player) {
            EventBus.MOVE_INPUT.post(new MoveInputEvent());
        }
    }

    private void playerUpdate(RuntimeEvents.PlayerUpdate event) {
        if (event.player() instanceof EntityPlayerSP player
                && Minecraft.getMinecraft().thePlayer == player) {
            PlayerUpdateEvent updateEvent = new PlayerUpdateEvent(Minecraft.getMinecraft());
            // A previous cancelled player update may never have reached sendPosition.
            // Interaction pins survive this ordinary submission cleanup.
            RotationLease.finishMotion();
            EventBus.PLAYER_UPDATE.post(updateEvent);
            if (updateEvent.isCancelled()) event.control().cancel();
        }
    }

    private void playerMove(RuntimeEvents.PlayerMove event) {
        Minecraft client = Minecraft.getMinecraft();
        if (!client.isCallingFromMinecraftThread()
                || !(event.player() instanceof EntityPlayerSP entity)
                || client.thePlayer != entity) return;
        MoveFix.State movementFix = MoveFix.current(client);
        if (!movementFix.active()) return;
        float yaw = movementFix.yaw();
        PositionCapture capture =
                positionCaptures.computeIfAbsent(entity, ignored -> new PositionCapture());
        capture.movementYaw = entity.rotationYaw;
        capture.movementActive = true;
        entity.rotationYaw = yaw;
    }

    private void playerMoveEnd(RuntimeEvents.PlayerMoveEnd event) {
        Minecraft client = Minecraft.getMinecraft();
        // Entity.moveRelative is also hooked for integrated-server players and mobs.
        // Their returns must never restore a client player's temporary movement yaw.
        if (!client.isCallingFromMinecraftThread()
                || !(event.player() instanceof EntityPlayerSP player)
                || client.thePlayer != player) return;
        restoreMovementYaw();
        EventBus.PLAYER_MOVE_END.post(new PlayerMoveEndEvent(player));
    }

    private void playerPosition(RuntimeEvents.PlayerPosition event) {
        if (!(event.player() instanceof EntityPlayerSP player)) return;
        PositionCapture capture =
                positionCaptures.computeIfAbsent(player, ignored -> new PositionCapture());
        if (event.phase() == RuntimeEvents.Phase.START) {
            capture.eventCameraYaw = player.rotationYaw;
            capture.eventCameraPitch = player.rotationPitch;
            RotationLease.beginMotion();
            applyPacketRotation(player, capture);
            capture.eventOutgoingYaw = player.rotationYaw;
            capture.eventOutgoingPitch = player.rotationPitch;
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

    private void applyPacketRotation(EntityPlayerSP player, PositionCapture state) {
        Rotation rotation;
        RotationManager.Decision decision = RotationManager.resolve();
        if (decision != null) {
            rotation = decision.rotation();
        } else {
            if (!state.continuous) return;
            Rotation base = RotationManager.start(Minecraft.getMinecraft());
            // Keep the camera itself in the same whole-turn domain before it is
            // captured for restoration. Correcting just this closing packet
            // would let the next vanilla packet jump from 181 back to -179.
            float cameraYaw = player.rotationYaw;
            float continuousYaw = RotationQuantizer.continuousYaw(base.yaw(), cameraYaw);
            player.rotationYaw = continuousYaw;
            player.prevRotationYaw += continuousYaw - cameraYaw;
            rotation =
                    new Rotation(
                            RotationQuantizer.yaw(base.yaw(), player.rotationYaw),
                            RotationQuantizer.pitch(base.pitch(), player.rotationPitch));
            state.continuous = false;
            applyTemporaryRotation(player, state, rotation);
            return;
        }
        state.continuous = true;
        applyTemporaryRotation(player, state, rotation);
    }

    private void applyTemporaryRotation(
            EntityPlayerSP player, PositionCapture state, Rotation rotation) {
        state.cameraYaw = player.rotationYaw;
        state.cameraPitch = player.rotationPitch;
        state.packetActive = true;
        player.rotationYaw = rotation.yaw();
        player.rotationPitch = rotation.pitch();
    }

    private void restorePacketRotation(EntityPlayerSP player, PositionCapture state) {
        if (state.packetActive) {
            player.rotationYaw = state.cameraYaw;
            player.rotationPitch = state.cameraPitch;
            state.packetActive = false;
        }
        // A completed method may have emitted no packet, or had its send cancelled.
        RotationLease.finishMotion();
    }

    private void renderState(RuntimeEvents.RenderState event) {
        if (event.entity() instanceof net.minecraft.entity.EntityLivingBase living) {
            if (Boolean.TRUE.equals(event.state()))
                renderRotations.begin(living, event.partialTick());
            else if (Boolean.FALSE.equals(event.state())) renderRotations.end(living);
        }
        if (EventBus.ENTITY_RENDER_STATE.listenerCount() != 0
                && event.entity() instanceof Entity entity
                && event.state() instanceof EntityRenderState state) {
            EventBus.ENTITY_RENDER_STATE.post(
                    new EntityRenderStateEvent(entity, state, event.partialTick()));
        }
    }

    private void rendererClose(RuntimeEvents.RendererClose event) {
        if (event.renderer() instanceof EntityRenderer renderer) {
            EventBus.RENDERER_CLOSE.post(new RendererCloseEvent(renderer));
        }
    }

    private void restoreMovementYaw() {
        Minecraft client = Minecraft.getMinecraft();
        EntityPlayerSP player = client.thePlayer;
        if (player == null) return;
        PositionCapture capture = positionCaptures.get(player);
        if (capture != null && capture.movementActive) {
            player.rotationYaw = capture.movementYaw;
            capture.movementActive = false;
        }
    }

    private static <K, V> Map<K, V> weakMap() {
        return Collections.synchronizedMap(new WeakHashMap<>());
    }

    private static final class ActionCapture {
        boolean attackActive;
        MovingObjectPosition attackHit;
        Entity attackTarget;
        boolean autoLavaCriticalEligible;
        boolean useActive;
        boolean manualUseActive;
        MovingObjectPosition manualUseHit;
        EntityPlayerSP usePlayer;
        MovingObjectPosition useHit;
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
