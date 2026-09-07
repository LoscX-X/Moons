package com.blanoir.moons.runtime;

import com.blanoir.moons.api.AgentMode;
import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;
import com.blanoir.moons.runtime.module.ModuleManager;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DefaultRuntimeBridge implements RuntimeBridge {
    private final AgentMode mode;
    private final Path home;
    private final RuntimeEvents events = new RuntimeEvents();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean firstTickReported = new AtomicBoolean();
    private final AtomicBoolean versionVerified = new AtomicBoolean();
    private final AtomicBoolean modulesStarted = new AtomicBoolean();
    private final AtomicBoolean unloadRequested = new AtomicBoolean();
    private final ModuleManager modules;
    private final String minecraftVersion;

    public DefaultRuntimeBridge(AgentMode mode, Path home, Path outerJar, String minecraftVersion)
            throws Exception {
        this.mode = mode;
        this.home = home;
        this.minecraftVersion =
                java.util.Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        this.modules = new ModuleManager(home, outerJar, events, minecraftVersion);
    }

    public RuntimeEvents events() {
        return events;
    }

    @Override
    public void onClientTickStart(Object minecraft) {
        if (!closed.get()) {
            if (versionVerified.compareAndSet(false, true)) {
                try {
                    MinecraftVersionGuard.verify(minecraft, minecraftVersion);
                } catch (Throwable failure) {
                    versionVerified.set(false);
                    if (failure instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    throw new IllegalStateException(
                            "Minecraft version verification failed", failure);
                }
            }
            startAndReloadModules();
            if (firstTickReported.compareAndSet(false, true)) {
                System.out.println(
                        Branding.prefix()
                                + " First client tick received from "
                                + minecraft.getClass().getName());
            }
            events.clientTick()
                    .publish(new RuntimeEvents.ClientTick(minecraft, RuntimeEvents.Phase.START));
        }
    }

    @Override
    public void onClientTickEnd(Object minecraft) {
        if (!closed.get()) {
            events.clientTick()
                    .publish(new RuntimeEvents.ClientTick(minecraft, RuntimeEvents.Phase.END));
            if (unloadRequested.compareAndSet(true, false)) {
                AgentBridge.uninstall(this);
                close();
            }
        }
    }

    @Override
    public void requestUnload() {
        if (!closed.get()) unloadRequested.set(true);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            modules.close();
            System.out.println(Branding.prefix() + " Runtime stopped (" + mode + ", " + home + ")");
        }
    }

    @Override
    public void onFrame(Object renderer, Object deltaTracker) {
        if (!closed.get()) events.frame().publish(new RuntimeEvents.Frame(renderer, deltaTracker));
    }

    @Override
    public void onHudRender(Object gui, Object extractor, Object deltaTracker) {
        if (!closed.get())
            events.hud().publish(new RuntimeEvents.Hud(gui, extractor, deltaTracker));
    }

    @Override
    public void onWorldRender(
            Object renderer, Object poseStack, Object levelRenderState, int stage) {
        if (!closed.get())
            events.worldRender()
                    .publish(
                            new RuntimeEvents.WorldRender(
                                    renderer, poseStack, levelRenderState, stage));
    }

    @Override
    public boolean onKey(Object handler, long window, int action, Object event) {
        RuntimeEvents.Control control = new RuntimeEvents.Control();
        if (!closed.get())
            events.key().publish(new RuntimeEvents.Key(handler, window, action, event, control));
        return !control.isCancelled();
    }

    @Override
    public boolean onMouse(Object handler, long window, Object button, int action) {
        RuntimeEvents.Control control = new RuntimeEvents.Control();
        if (!closed.get())
            events.mouse()
                    .publish(new RuntimeEvents.Mouse(handler, window, button, action, control));
        return !control.isCancelled();
    }

    @Override
    public boolean onMouseScroll(Object handler, long window, double xOffset, double yOffset) {
        RuntimeEvents.Control control = new RuntimeEvents.Control();
        if (!closed.get())
            events.mouseScroll()
                    .publish(
                            new RuntimeEvents.MouseScroll(
                                    handler, window, xOffset, yOffset, control));
        return !control.isCancelled();
    }

    @Override
    public boolean onAttack(Object minecraft) {
        return publishAction(minecraft, RuntimeEvents.Kind.ATTACK);
    }

    @Override
    public boolean onUse(Object minecraft) {
        return publishAction(minecraft, RuntimeEvents.Kind.USE);
    }

    @Override
    public void onAttackEnd(Object minecraft) {
        publishActionEnd(minecraft, RuntimeEvents.Kind.ATTACK);
    }

    @Override
    public void onUseEnd(Object minecraft) {
        publishActionEnd(minecraft, RuntimeEvents.Kind.USE);
    }

    @Override
    public boolean onPacketSend(Object connection, Object packet) {
        RuntimeEvents.Control control = new RuntimeEvents.Control();
        if (!closed.get())
            events.packet()
                    .publish(
                            new RuntimeEvents.Packet(
                                    connection,
                                    packet,
                                    RuntimeEvents.PacketPhase.SEND_PRE,
                                    control));
        return !control.isCancelled();
    }

    @Override
    public void onPacketSent(Object connection, Object packet) {
        if (!closed.get())
            events.packet()
                    .publish(
                            new RuntimeEvents.Packet(
                                    connection,
                                    packet,
                                    RuntimeEvents.PacketPhase.SEND_POST,
                                    new RuntimeEvents.Control()));
    }

    @Override
    public boolean onPacketReceive(Object packet, Object listener) {
        RuntimeEvents.Control control = new RuntimeEvents.Control();
        if (!closed.get())
            events.packet()
                    .publish(
                            new RuntimeEvents.Packet(
                                    listener,
                                    packet,
                                    RuntimeEvents.PacketPhase.RECEIVE_NETWORK,
                                    control));
        return !control.isCancelled();
    }

    @Override
    public void onPacketApply(Object packet, Object listener) {
        if (!closed.get())
            events.packet()
                    .publish(
                            new RuntimeEvents.Packet(
                                    listener,
                                    packet,
                                    RuntimeEvents.PacketPhase.RECEIVE_APPLY,
                                    new RuntimeEvents.Control()));
    }

    @Override
    public void onBlockUpdate(Object level, Object position, Object newState, boolean applied) {
        if (!closed.get())
            events.blockUpdate()
                    .publish(
                            new RuntimeEvents.BlockUpdate(
                                    level, position, newState, RuntimeEvents.Phase.END, applied));
    }

    @Override
    public void onBlockUpdateStart(Object level, Object position, Object newState) {
        if (!closed.get())
            events.blockUpdate()
                    .publish(
                            new RuntimeEvents.BlockUpdate(
                                    level, position, newState, RuntimeEvents.Phase.START, false));
    }

    @Override
    public boolean onPlayerUpdate(Object player) {
        RuntimeEvents.Control control = new RuntimeEvents.Control();
        if (!closed.get()) {
            events.playerUpdate().publish(new RuntimeEvents.PlayerUpdate(player, control));
        }
        return !control.isCancelled();
    }

    @Override
    public void onMoveInput(Object player) {
        if (!closed.get()) events.moveInput().publish(new RuntimeEvents.MoveInput(player));
    }

    @Override
    public Object[] onPlayerMove(Object player, float scale, Object movement) {
        RuntimeEvents.PlayerMove event = new RuntimeEvents.PlayerMove(player, scale, movement);
        if (!closed.get()) events.playerMove().publish(event);
        return new Object[] {event.scale(), event.movement()};
    }

    @Override
    public void onPlayerMoveEnd(Object player) {
        if (!closed.get()) events.playerMoveEnd().publish(new RuntimeEvents.PlayerMoveEnd(player));
    }

    @Override
    public void onMouseMoveStart(Object handler) {
        if (!closed.get())
            events.mouseMove()
                    .publish(new RuntimeEvents.MouseMove(handler, RuntimeEvents.Phase.START));
    }

    @Override
    public void onMouseMoveEnd(Object handler) {
        if (!closed.get())
            events.mouseMove()
                    .publish(new RuntimeEvents.MouseMove(handler, RuntimeEvents.Phase.END));
    }

    @Override
    public void onPlayerPositionStart(Object player) {
        if (!closed.get())
            events.playerPosition()
                    .publish(new RuntimeEvents.PlayerPosition(player, RuntimeEvents.Phase.START));
    }

    @Override
    public void onPlayerPositionEnd(Object player) {
        if (!closed.get())
            events.playerPosition()
                    .publish(new RuntimeEvents.PlayerPosition(player, RuntimeEvents.Phase.END));
    }

    @Override
    public void onRenderState(Object entity, Object state, float partialTick) {
        if (!closed.get())
            events.renderState().publish(new RuntimeEvents.RenderState(entity, state, partialTick));
    }

    @Override
    public void onRendererClose(Object renderer) {
        if (!closed.get())
            events.rendererClose().publish(new RuntimeEvents.RendererClose(renderer));
    }

    @Override
    public void onVoidHook(String id, Object owner, Object argument) {
        if (!closed.get())
            events.methodHook().publish(new RuntimeEvents.MethodHook(id, owner, argument, null));
    }

    @Override
    public Object onObjectValue(String id, Object owner, Object argument, Object value) {
        RuntimeEvents.MethodHook event = new RuntimeEvents.MethodHook(id, owner, argument, value);
        if (!closed.get()) events.methodHook().publish(event);
        return event.value();
    }

    @Override
    public boolean onBooleanValue(String id, Object owner, Object argument, boolean value) {
        RuntimeEvents.MethodHook event = new RuntimeEvents.MethodHook(id, owner, argument, value);
        if (!closed.get()) events.methodHook().publish(event);
        return event.value() instanceof Boolean replacement ? replacement : value;
    }

    @Override
    public float onFloatValue(String id, Object owner, float argument, float value) {
        RuntimeEvents.MethodHook event = new RuntimeEvents.MethodHook(id, owner, argument, value);
        if (!closed.get()) events.methodHook().publish(event);
        return event.value() instanceof Number replacement ? replacement.floatValue() : value;
    }

    private void startAndReloadModules() {
        try {
            if (modulesStarted.compareAndSet(false, true)) {
                try {
                    modules.start();
                } catch (Throwable failure) {
                    modulesStarted.set(false);
                    throw failure;
                }
            }
            modules.drainReloads();
        } catch (Throwable failure) {
            System.err.println(
                    Branding.prefix() + " Module operation failed on client thread: " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private boolean publishAction(Object minecraft, RuntimeEvents.Kind kind) {
        RuntimeEvents.Control control = new RuntimeEvents.Control();
        if (!closed.get())
            events.action()
                    .publish(
                            new RuntimeEvents.Action(
                                    minecraft, kind, RuntimeEvents.Phase.START, control));
        return !control.isCancelled();
    }

    private void publishActionEnd(Object minecraft, RuntimeEvents.Kind kind) {
        if (!closed.get())
            events.action()
                    .publish(
                            new RuntimeEvents.Action(
                                    minecraft,
                                    kind,
                                    RuntimeEvents.Phase.END,
                                    new RuntimeEvents.Control()));
    }
}
