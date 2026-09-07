package com.blanoir.moons.api.bridge;

/**
 * Loader-neutral boundary called by loaded bytecode.
 *
 * <p>Only JDK types and {@code Object} may appear here: this interface is
 * loaded by the bootstrap loader and must never resolve a Minecraft class.
 */
public interface RuntimeBridge {
    RuntimeBridge NOOP = new NoopRuntimeBridge();

    default void onClientTickStart(Object minecraft) {}

    default void onClientTickEnd(Object minecraft) {}

    default void onFrame(Object renderer, Object deltaTracker) {}

    default void onHudRender(Object gui, Object graphicsExtractor, Object deltaTracker) {}

    default void onWorldRender(
            Object renderer, Object poseStack, Object levelRenderState, int stage) {}

    default boolean onKey(Object handler, long window, int action, Object event) {
        return true;
    }

    default boolean onMouse(Object handler, long window, Object button, int action) {
        return true;
    }

    default boolean onMouseScroll(Object handler, long window, double xOffset, double yOffset) {
        return true;
    }

    default void onMouseMoveStart(Object handler) {}

    default void onMouseMoveEnd(Object handler) {}

    default boolean onAttack(Object minecraft) {
        return true;
    }

    default void onAttackEnd(Object minecraft) {}

    default boolean onUse(Object minecraft) {
        return true;
    }

    default void onUseEnd(Object minecraft) {}

    default boolean onPacketSend(Object connection, Object packet) {
        return true;
    }

    default void onPacketSent(Object connection, Object packet) {}

    default boolean onPacketReceive(Object packet, Object listener) {
        return true;
    }

    default void onPacketApply(Object packet, Object listener) {}

    default void onBlockUpdateStart(Object level, Object position, Object newState) {}

    default void onBlockUpdate(Object level, Object position, Object newState, boolean applied) {}

    default boolean onPlayerUpdate(Object player) {
        return true;
    }

    default void onMoveInput(Object player) {}

    default Object[] onPlayerMove(Object player, float scale, Object movement) {
        return new Object[] {scale, movement};
    }

    default void onPlayerMoveEnd(Object player) {}

    default void onPlayerPositionStart(Object player) {}

    default void onPlayerPositionEnd(Object player) {}

    default void onRenderState(Object entity, Object renderState, float partialTick) {}

    default void onRendererClose(Object renderer) {}

    default void onVoidHook(String id, Object owner, Object argument) {}

    default Object onObjectValue(String id, Object owner, Object argument, Object value) {
        return value;
    }

    default boolean onBooleanValue(String id, Object owner, Object argument, boolean value) {
        return value;
    }

    default float onFloatValue(String id, Object owner, float argument, float value) {
        return value;
    }

    /** Requests a deferred, clean runtime shutdown. */
    default void requestUnload() {}

    default void close() {}
}
