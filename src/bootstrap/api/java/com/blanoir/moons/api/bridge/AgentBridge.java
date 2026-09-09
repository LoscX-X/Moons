package com.blanoir.moons.api.bridge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Bootstrap-visible, fail-open entrypoints used by every ASM hook. */
public final class AgentBridge {
    private static final AtomicInteger REPORTED_FAILURES = new AtomicInteger();
    private static volatile RuntimeBridge runtime = RuntimeBridge.NOOP;

    private AgentBridge() {}

    public static synchronized RuntimeBridge install(RuntimeBridge next) {
        RuntimeBridge previous = runtime;
        runtime = Objects.requireNonNull(next, "next");
        return previous;
    }

    public static synchronized void uninstall(RuntimeBridge expected) {
        if (runtime == expected) {
            runtime = RuntimeBridge.NOOP;
        }
    }

    public static synchronized boolean isInstalled(RuntimeBridge expected) {
        return runtime == expected;
    }

    public static void onClientTickStart(Object minecraft) {
        try {
            runtime.onClientTickStart(minecraft);
        } catch (Throwable failure) {
            report("client tick start", failure);
        }
    }

    public static void onClientTickEnd(Object minecraft) {
        try {
            runtime.onClientTickEnd(minecraft);
        } catch (Throwable failure) {
            report("client tick end", failure);
        }
    }

    public static void onFrame(Object renderer, Object deltaTracker) {
        try {
            runtime.onFrame(renderer, deltaTracker);
        } catch (Throwable failure) {
            report("frame", failure);
        }
    }

    public static void onHudRender(Object gui, Object extractor, Object deltaTracker) {
        try {
            runtime.onHudRender(gui, extractor, deltaTracker);
        } catch (Throwable failure) {
            report("hud render", failure);
        }
    }

    public static void onWorldRender(
            Object renderer, Object poseStack, Object levelRenderState, int stage) {
        try {
            runtime.onWorldRender(renderer, poseStack, levelRenderState, stage);
        } catch (Throwable failure) {
            report("world render", failure);
        }
    }

    public static boolean onKey(Object handler, long window, int action, Object event) {
        try {
            return runtime.onKey(handler, window, action, event);
        } catch (Throwable failure) {
            report("key", failure);
            return true;
        }
    }

    public static boolean onMouse(Object handler, long window, Object button, int action) {
        try {
            return runtime.onMouse(handler, window, button, action);
        } catch (Throwable failure) {
            report("mouse", failure);
            return true;
        }
    }

    public static boolean onMouseScroll(
            Object handler, long window, double xOffset, double yOffset) {
        try {
            return runtime.onMouseScroll(handler, window, xOffset, yOffset);
        } catch (Throwable failure) {
            report("mouse scroll", failure);
            return true;
        }
    }

    public static void onMouseMoveStart(Object handler) {
        try {
            runtime.onMouseMoveStart(handler);
        } catch (Throwable failure) {
            report("mouse move start", failure);
        }
    }

    public static void onMouseMoveEnd(Object handler) {
        try {
            runtime.onMouseMoveEnd(handler);
        } catch (Throwable failure) {
            report("mouse move end", failure);
        }
    }

    public static boolean onAttack(Object minecraft) {
        try {
            return runtime.onAttack(minecraft);
        } catch (Throwable failure) {
            report("attack", failure);
            return true;
        }
    }

    public static void onAttackEnd(Object minecraft) {
        try {
            runtime.onAttackEnd(minecraft);
        } catch (Throwable failure) {
            report("attack end", failure);
        }
    }

    public static boolean onUse(Object minecraft) {
        try {
            return runtime.onUse(minecraft);
        } catch (Throwable failure) {
            report("use", failure);
            return true;
        }
    }

    public static void onUseEnd(Object minecraft) {
        try {
            runtime.onUseEnd(minecraft);
        } catch (Throwable failure) {
            report("use end", failure);
        }
    }

    public static boolean onPacketSend(Object connection, Object packet) {
        try {
            return runtime.onPacketSend(connection, packet);
        } catch (Throwable failure) {
            report("packet send", failure);
            return true;
        }
    }

    public static void onPacketSent(Object connection, Object packet) {
        try {
            runtime.onPacketSent(connection, packet);
        } catch (Throwable failure) {
            report("packet sent", failure);
        }
    }

    public static boolean onPacketReceive(Object packet, Object listener) {
        try {
            return runtime.onPacketReceive(packet, listener);
        } catch (Throwable failure) {
            report("packet receive", failure);
            return true;
        }
    }

    public static void onPacketApply(Object packet, Object listener) {
        try {
            runtime.onPacketApply(packet, listener);
        } catch (Throwable failure) {
            report("packet apply", failure);
        }
    }

    public static void onBlockUpdateStart(Object level, Object position, Object newState) {
        try {
            runtime.onBlockUpdateStart(level, position, newState);
        } catch (Throwable failure) {
            report("block update start", failure);
        }
    }

    public static void onBlockUpdate(
            Object level, Object position, Object newState, boolean applied) {
        try {
            runtime.onBlockUpdate(level, position, newState, applied);
        } catch (Throwable failure) {
            report("block update", failure);
        }
    }

    public static boolean onPlayerUpdate(Object player) {
        try {
            return runtime.onPlayerUpdate(player);
        } catch (Throwable failure) {
            report("player update", failure);
            return true;
        }
    }

    public static void onMoveInput(Object player) {
        try {
            runtime.onMoveInput(player);
        } catch (Throwable failure) {
            report("move input", failure);
        }
    }

    public static Object[] onPlayerMove(Object player, float scale, Object movement) {
        try {
            Object[] replacement = runtime.onPlayerMove(player, scale, movement);
            if (replacement == null
                    || replacement.length < 2
                    || !(replacement[0] instanceof Number)
                    || replacement[1] == null) {
                return new Object[] {scale, movement};
            }
            return replacement;
        } catch (Throwable failure) {
            report("player move", failure);
            return new Object[] {scale, movement};
        }
    }

    public static void onPlayerMoveEnd(Object player) {
        try {
            runtime.onPlayerMoveEnd(player);
        } catch (Throwable failure) {
            report("player move end", failure);
        }
    }

    public static void onPlayerPositionStart(Object player) {
        try {
            runtime.onPlayerPositionStart(player);
        } catch (Throwable failure) {
            report("player position start", failure);
        }
    }

    public static void onPlayerPositionEnd(Object player) {
        try {
            runtime.onPlayerPositionEnd(player);
        } catch (Throwable failure) {
            report("player position end", failure);
        }
    }

    public static void onRenderState(Object entity, Object state, float partialTick) {
        try {
            runtime.onRenderState(entity, state, partialTick);
        } catch (Throwable failure) {
            report("render state", failure);
        }
    }

    public static void onRendererClose(Object renderer) {
        try {
            runtime.onRendererClose(renderer);
        } catch (Throwable failure) {
            report("renderer close", failure);
        }
    }

    public static boolean isHookActive(String id) {
        RuntimeBridge current = runtime;
        if (current == RuntimeBridge.NOOP) return false;
        try {
            return current.isHookActive(id);
        } catch (Throwable failure) {
            report(id, failure);
            return true;
        }
    }

    public static void onVoidHook(String id, Object owner, Object argument) {
        try {
            runtime.onVoidHook(id, owner, argument);
        } catch (Throwable failure) {
            report(id, failure);
        }
    }

    public static Object onObjectValue(String id, Object owner, Object argument, Object value) {
        try {
            Object replacement = runtime.onObjectValue(id, owner, argument, value);
            return replacement == null ? value : replacement;
        } catch (Throwable failure) {
            report(id, failure);
            return value;
        }
    }

    public static boolean onBooleanValue(String id, Object owner, Object argument, boolean value) {
        try {
            return runtime.onBooleanValue(id, owner, argument, value);
        } catch (Throwable failure) {
            report(id, failure);
            return value;
        }
    }

    public static float onFloatValue(String id, Object owner, float argument, float value) {
        try {
            return runtime.onFloatValue(id, owner, argument, value);
        } catch (Throwable failure) {
            report(id, failure);
            return value;
        }
    }

    public static void requestUnload() {
        try {
            runtime.requestUnload();
        } catch (Throwable failure) {
            report("runtime unload", failure);
        }
    }

    private static void report(String hook, Throwable failure) {
        if (REPORTED_FAILURES.getAndIncrement() < 8) {
            System.err.println(brandingPrefix() + " Runtime failure in " + hook + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    /** Kept JDK-only because this class is also defined in isolated game loaders. */
    private static String brandingPrefix() {
        String name = normalizeName(System.getProperty("moons.name", ""));
        if (name.isEmpty()) name = normalizeName(System.getenv("MOONS_NAME"));
        return "[" + (name.isEmpty() ? "Moons" : name) + "]";
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }
}
