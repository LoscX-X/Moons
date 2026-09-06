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
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.event.network.PacketThread;
import com.blanoir.moons.client.event.tick.TickEndEvent;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.event.render.EntityRenderStateEvent;
import com.blanoir.moons.client.event.render.RendererCloseEvent;
import com.blanoir.moons.client.event.world.BlockUpdateEvent;
import com.blanoir.moons.client.management.rotation.MoveFix;
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
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.input.MouseInputTracker;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.rotation.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationHistory;
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
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
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
    private final Map<Avatar, BodyRotationState> bodyRotations = weakMap();
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
        EventBus.CLIENT_CONTEXT_CHANGED.post(new ClientContextChangedEvent(
                client, previous, current));
    }

    private static ClientContextChangedEvent.Snapshot contextOf(Minecraft client) {
        return new ClientContextChangedEvent.Snapshot(
                client == null ? null : client.level,
                client == null ? null : client.player,
                client == null ? null : client.getConnection());
    }

    private static boolean sameContext(
            ClientContextChangedEvent.Snapshot first,
            ClientContextChangedEvent.Snapshot second
    ) {
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
        float partialTick = tracker == null ? 1.0F
                : tracker.getGameTimeDeltaPartialTick(true);
        WorldRenderDispatch.post(poseStack, partialTick);
    }

    private void key(RuntimeEvents.Key event) {
        if (event.handler() instanceof KeyboardHandler handler
                && event.event() instanceof KeyEvent keyEvent) {
            KeyInputEvent input = new KeyInputEvent(
                    handler, event.window(), event.action(), keyEvent);
            EventBus.KEY_INPUT.post(input);
            if (input.isCancelled()) {
                event.control().cancel();
                return;
            }
        }
        if (event.action() != GLFW.GLFW_PRESS || !(event.event() instanceof KeyEvent keyEvent)) return;
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
            MouseButtonEvent input = new MouseButtonEvent(
                    handler, event.window(), button, event.action());
            EventBus.MOUSE_BUTTON.post(input);
            if (input.isCancelled()) {
                event.control().cancel();
                return;
            }
        }
        if (event.action() != GLFW.GLFW_PRESS || !(event.button() instanceof MouseButtonInfo button)) return;
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
            RuntimeEvents.Control control
    ) {
        ModuleKeybinds.Dispatch dispatch = ModuleKeybinds.dispatchPress(
                key, allowGameplayBindings);
        if (dispatch == ModuleKeybinds.Dispatch.GUI) {
            ModuleGui.toggle(client);
        }
        if (dispatch.consumed()) {
            control.cancel();
        }
    }

    private void mouseScroll(RuntimeEvents.MouseScroll event) {
        if (event.handler() instanceof MouseHandler handler) {
            MouseScrollEvent input = new MouseScrollEvent(
                    handler, event.window(), event.xOffset(), event.yOffset());
            EventBus.MOUSE_SCROLL.post(input);
            if (input.isCancelled()) {
                event.control().cancel();
                return;
            }
        }
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer == null || client.level == null
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
                mouseCaptures.put(event.handler(), new MouseCapture(player.getYRot(), player.getXRot()));
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
            if (event.kind() == RuntimeEvents.Kind.USE && manualRotation != null && player != null
                    && !RotationHistory.same(manualRotation,
                    new Rotation(player.getYRot(), player.getXRot()))) {
                event.control().cancel();
                return;
            }
            if (publishActionPre(client, event.kind())) {
                event.control().cancel();
                return;
            }
            if (event.kind() == RuntimeEvents.Kind.USE
                    && (Scaffold.cancelUseAction()
                    || SilentAura.shouldSuppressUseAction(client))) {
                event.control().cancel();
                return;
            }
            if (event.kind() == RuntimeEvents.Kind.ATTACK
                    && Reach.handleManualAttack(client)) {
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
        ActionCapture capture = actionCaptures.computeIfAbsent(client, ignored -> new ActionCapture());
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
                Backtrack.onAttack(target);
                AutoTool.onAttack(target);
                AutoSword.onAttack(target);
                // Auto placement is committed from the END hook, after vanilla
                // has dispatched ATTACK. Preserve the pre-hit critical sample:
                // attack strength is reset synchronously by the packet path.
                capture.attackTarget = target;
                capture.autoLavaCriticalEligible =
                        AutoLava.isCriticalTriggerEligible(target);
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
        Packet<?> packet = (Packet<?>) event.packet();
        PacketThread thread = Minecraft.getInstance().isSameThread()
                ? PacketThread.CLIENT : PacketThread.NETWORK;
        switch (event.phase()) {
            case SEND_PRE -> {
                PacketSendEvent.Pre packetEvent = new PacketSendEvent.Pre(
                        (Connection) event.connection(), packet, thread);
                EventBus.PACKET_SEND_PRE.post(packetEvent);
                if (packetEvent.isCancelled()) event.control().cancel();
            }
            case SEND_POST -> {
                RotationLease.beginPacketObservation();
                try {
                    EventBus.PACKET_SEND_POST.post(new PacketSendEvent.Post(
                            (Connection) event.connection(), packet, thread));
                } finally {
                    RotationLease.endPacketObservation();
                }
            }
            case RECEIVE_NETWORK -> {
                if (thread == PacketThread.CLIENT) return;
                PacketReceiveEvent.Pre packetEvent = new PacketReceiveEvent.Pre(
                        packet, (PacketListener) event.connection(), PacketThread.NETWORK);
                EventBus.PACKET_RECEIVE_PRE.post(packetEvent);
                if (packetEvent.bundleExpansionRequested()
                        && packet instanceof ClientboundBundlePacket bundle) {
                    event.control().cancel();
                    bundle.subPackets().forEach(subPacket -> GameAccess.dispatchIncoming(
                            subPacket, (PacketListener) event.connection()));
                } else if (packetEvent.isCancelled()) {
                    event.control().cancel();
                }
            }
            case RECEIVE_APPLY -> EventBus.PACKET_RECEIVE_APPLY.post(new PacketReceiveEvent.Apply(
                    packet, (PacketListener) event.connection()));
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
            EventBus.BLOCK_UPDATE_PRE.post(new BlockUpdateEvent.Pre(
                    level, position, previous, state));
            return;
        }
        BlockState previous = stack.isEmpty() ? state : stack.pop();
        if (event.applied()) {
            AntiLava.onBlockUpdate(position, state);
            AntiWeb.onBlockUpdate(position, previous, state);
        }
        OreScanner.queueBlockUpdate(position);
        EventBus.BLOCK_UPDATE_POST.post(new BlockUpdateEvent.Post(
                level, position, previous, state, event.applied()));
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
        if (!(event.player() instanceof Entity entity)
                || Minecraft.getInstance().player != entity) return;
        Minecraft client = Minecraft.getInstance();
        if (event.movement() instanceof Vec3 movement) {
            float initialStrafe = (float) movement.x;
            float initialForward = (float) movement.z;
            float initialFriction = event.scale();
            StrafeEvent strafe = new StrafeEvent(
                    initialStrafe, initialForward, initialFriction);
            EventBus.STRAFE.post(strafe);
            // Preserve vanilla's original double vector unless a listener
            // actually changes it. Rebuilding it from floats every move was
            // an old Strafe side effect and introduced needless prediction
            // drift even in the Vanilla Telly/Tower path.
            if (Float.compare(strafe.getStrafe(), initialStrafe) != 0
                    || Float.compare(strafe.getForward(), initialForward) != 0) {
                event.movement(new Vec3(
                        strafe.getStrafe(), movement.y, strafe.getForward()));
            }
            if (Float.compare(strafe.getFriction(), initialFriction) != 0) {
                event.scale(strafe.getFriction());
            }
        }
        MoveFix.State movementFix = MoveFix.current(client);
        if (!movementFix.active()) return;
        float yaw = movementFix.yaw();
        PositionCapture capture = positionCaptures.computeIfAbsent(entity, ignored -> new PositionCapture());
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
        PositionCapture capture = positionCaptures.computeIfAbsent(player, ignored -> new PositionCapture());
        if (event.phase() == RuntimeEvents.Phase.START) {
            capture.eventCameraYaw = player.getYRot();
            capture.eventCameraPitch = player.getXRot();
            RotationLease.beginMotion();
            applyPacketRotation(player, capture);
            capture.eventOutgoingYaw = player.getYRot();
            capture.eventOutgoingPitch = player.getXRot();
            capture.eventOverridden = Float.compare(
                    capture.eventCameraYaw, capture.eventOutgoingYaw) != 0
                    || Float.compare(capture.eventCameraPitch, capture.eventOutgoingPitch) != 0;
            EventBus.PLAYER_MOTION_PRE.post(new PlayerMotionEvent.Pre(
                    player,
                    capture.eventCameraYaw,
                    capture.eventCameraPitch,
                    capture.eventOutgoingYaw,
                    capture.eventOutgoingPitch,
                    capture.eventOverridden));
        } else {
            PlayerMotionEvent.Post motion = new PlayerMotionEvent.Post(
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
            rotation = new Rotation(
                    SilentPacketRotation.quantizePacketYaw(base.yaw(), player.getYRot()),
                    SilentPacketRotation.quantizePacketPitch(base.pitch(), player.getXRot()));
            state.continuous = false;
            applyTemporaryRotation(player, state, rotation);
            return;
        }
        state.continuous = true;
        applyTemporaryRotation(player, state, rotation);
    }

    private void applyTemporaryRotation(LocalPlayer player, PositionCapture state, Rotation rotation) {
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
            applySilentBodyRotation(avatar, state, event.partialTick());
            Animations.applyThirdPerson(avatar, state);
        }
        if (event.entity() instanceof Entity entity
                && event.state() instanceof EntityRenderState state) {
            EventBus.ENTITY_RENDER_STATE.post(new EntityRenderStateEvent(
                    entity, state, event.partialTick()));
        }
    }

    private void rendererClose(RuntimeEvents.RendererClose event) {
        if (event.renderer() instanceof GameRenderer renderer) {
            EventBus.RENDERER_CLOSE.post(new RendererCloseEvent(renderer));
        }
    }

    private void applySilentBodyRotation(
            Avatar avatar, AvatarRenderState state, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || avatar.getId() != player.getId()) return;

        BodyRotationState rotation = bodyRotations.computeIfAbsent(
                avatar, ignored -> new BodyRotationState());
        float vanillaBodyYaw = state.bodyRot;
        float vanillaHeadYaw = vanillaBodyYaw + state.yRot;
        boolean packet = SilentPacketRotation.shouldApplyRotation();
        boolean scaffold = !packet && Scaffold.shouldApplyRotation();
        boolean aura = !packet && !scaffold && SilentAura.shouldApplyRotation();
        if (packet || aura || scaffold) {
            long now = System.nanoTime();
            if (!rotation.active) {
                rotation.bodyYaw = state.bodyRot;
                rotation.active = true;
                rotation.lastFrameNanos = now;
                rotation.velocity = 0.0F;
            }
            float seconds = frameSeconds(rotation, now);
            float silentYaw = scaffold ? Scaffold.getRenderYaw()
                    : aura ? SilentAura.getYaw()
                    : SilentPacketRotation.getYaw();
            float silentPitch = scaffold ? Scaffold.getRenderPitch()
                    : aura ? SilentAura.getPitch()
                    : SilentPacketRotation.getPitch();
            boolean fullLock = aura && SilentAura.isFullLockMode();
            if (fullLock) {
                updateFullLockRenderRotation(rotation, silentYaw, silentPitch,
                        vanillaHeadYaw, state.xRot, player.tickCount, partialTick);
                silentYaw = rotation.fullLockRenderYaw;
                silentPitch = rotation.fullLockRenderPitch;
            } else {
                rotation.fullLockRenderActive = false;
                rotation.fullLockRenderTick = Integer.MIN_VALUE;
            }
            boolean aggressiveBody = aura && SilentAura.isLockMode();
            if (aura && SilentAura.isCrossingTarget()) {
                // While the eye is inside the opponent, keep the absolute head
                // at its entry angle and let the body perform the horizontal
                // turn. This avoids the characteristic head-down/head-flip
                // frame produced by locking an interior point of the AABB.
                stepBodyYaw(rotation, SilentAura.getBodyYaw(), seconds, aggressiveBody);
            } else if (aggressiveBody) {
                // Lock is allowed to move the head quickly, so its body must
                // continuously pursue that same absolute yaw. The old slack
                // calculation only advanced a fraction of the remaining gap
                // and could leave the torso behind while both players strafed.
                stepBodyYaw(rotation, silentYaw, seconds, true);
            } else {
                float fromBody = Mth.wrapDegrees(silentYaw - rotation.bodyYaw);
                // The body must keep up with silent head snaps: a large head-body
                // delta both looks wrong to other players and feeds head/body
                // correlation checks, so react early and turn hard.
                float headSlack = aggressiveBody ? 3.0F : 10.0F;
                float turn = Math.max(
                        Math.max(0.0F, Math.abs(fromBody) - headSlack)
                                * (aggressiveBody ? 1.15F : 0.85F),
                        Math.max(0.0F, Math.abs(fromBody) - (aggressiveBody ? 18.0F : 36.0F)));
                stepBodyYaw(rotation, rotation.bodyYaw + Math.copySign(turn, fromBody),
                        seconds, aggressiveBody);
            }
            state.bodyRot = rotation.bodyYaw;
            state.yRot = Mth.wrapDegrees(silentYaw - state.bodyRot);
            state.xRot = silentPitch;
            return;
        }

        if (!rotation.active) return;
        rotation.fullLockRenderActive = false;
        rotation.fullLockRenderTick = Integer.MIN_VALUE;
        long now = System.nanoTime();
        float seconds = frameSeconds(rotation, now);
        float difference = Mth.wrapDegrees(vanillaBodyYaw - rotation.bodyYaw);
        stepBodyYaw(rotation, vanillaBodyYaw, seconds);
        state.bodyRot = rotation.bodyYaw;
        state.yRot = Mth.wrapDegrees(vanillaHeadYaw - state.bodyRot);
        if (Math.abs(difference) <= 0.5F) {
            rotation.active = false;
            rotation.lastFrameNanos = 0L;
            rotation.velocity = 0.0F;
        }
    }

    /** Interpolates the tick target in local render state without changing packets. */
    private static void updateFullLockRenderRotation(
            BodyRotationState state,
            float targetYaw,
            float targetPitch,
            float fallbackYaw,
            float fallbackPitch,
            int tick,
            float partialTick
    ) {
        if (!state.fullLockRenderActive) {
            state.fullLockRenderActive = true;
            state.fullLockRenderTick = tick;
            state.fullLockPreviousYaw = fallbackYaw;
            state.fullLockPreviousPitch = fallbackPitch;
        } else if (state.fullLockRenderTick != tick) {
            state.fullLockRenderTick = tick;
            state.fullLockPreviousYaw = state.fullLockTargetYaw;
            state.fullLockPreviousPitch = state.fullLockTargetPitch;
        }

        state.fullLockTargetYaw = state.fullLockPreviousYaw
                + Mth.wrapDegrees(targetYaw - state.fullLockPreviousYaw);
        state.fullLockTargetPitch = Mth.clamp(targetPitch, -90.0F, 90.0F);
        float progress = Mth.clamp(partialTick, 0.0F, 1.0F);
        state.fullLockRenderYaw = state.fullLockPreviousYaw
                + Mth.wrapDegrees(state.fullLockTargetYaw - state.fullLockPreviousYaw)
                * progress;
        state.fullLockRenderPitch = Mth.lerp(
                progress, state.fullLockPreviousPitch, state.fullLockTargetPitch);
    }

    private static float frameSeconds(BodyRotationState state, long now) {
        float result = (float) Mth.clamp(
                (now - state.lastFrameNanos) / 1_000_000_000.0D,
                1.0D / 240.0D,
                0.1D);
        state.lastFrameNanos = now;
        return result;
    }

    private static void stepBodyYaw(BodyRotationState state, float targetYaw, float seconds) {
        stepBodyYaw(state, targetYaw, seconds, false);
    }

    private static void stepBodyYaw(
            BodyRotationState state,
            float targetYaw,
            float seconds,
            boolean aggressive
    ) {
        float difference = Mth.wrapDegrees(targetYaw - state.bodyYaw);
        float response = aggressive ? 28.0F : 14.0F;
        float maxSpeed = aggressive ? 900.0F : 420.0F;
        float desiredVelocity = Mth.clamp(difference * response, -maxSpeed, maxSpeed);
        float velocityChange = (aggressive ? 5_000.0F : 1_600.0F) * seconds;
        state.velocity = Mth.clamp(
                desiredVelocity,
                state.velocity - velocityChange,
                state.velocity + velocityChange);
        float step = state.velocity * seconds;
        if (Math.signum(step) == Math.signum(difference)
                && Math.abs(step) > Math.abs(difference)) {
            step = difference;
            state.velocity = 0.0F;
        }
        state.bodyYaw += step;
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

    private record MouseCapture(float yaw, float pitch) { }

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

    private static final class BodyRotationState {
        boolean active;
        float bodyYaw;
        long lastFrameNanos;
        float velocity;
        boolean fullLockRenderActive;
        int fullLockRenderTick = Integer.MIN_VALUE;
        float fullLockPreviousYaw;
        float fullLockPreviousPitch;
        float fullLockTargetYaw;
        float fullLockTargetPitch;
        float fullLockRenderYaw;
        float fullLockRenderPitch;
    }
}
