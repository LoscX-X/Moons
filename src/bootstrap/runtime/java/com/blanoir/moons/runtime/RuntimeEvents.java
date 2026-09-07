package com.blanoir.moons.runtime;

import com.blanoir.moons.runtime.event.EventChannel;

public final class RuntimeEvents {
    public record ClientTick(Object minecraft, Phase phase) {}

    public enum Phase {
        START,
        END
    }

    public static final class Control {
        private boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    public record Frame(Object renderer, Object deltaTracker) {}

    public record Hud(Object gui, Object extractor, Object deltaTracker) {}

    public record WorldRender(
            Object renderer, Object poseStack, Object levelRenderState, int stage) {}

    public record Key(Object handler, long window, int action, Object event, Control control) {}

    public record Mouse(Object handler, long window, Object button, int action, Control control) {}

    public record MouseScroll(
            Object handler, long window, double xOffset, double yOffset, Control control) {}

    public record Action(Object minecraft, Kind kind, Phase phase, Control control) {}

    public record Packet(Object connection, Object packet, PacketPhase phase, Control control) {}

    public record BlockUpdate(
            Object level, Object position, Object newState, Phase phase, boolean applied) {}

    public record PlayerUpdate(Object player, Control control) {}

    public record MoveInput(Object player) {}

    public static final class PlayerMove {
        private final Object player;
        private float scale;
        private Object movement;

        public PlayerMove(Object player, float scale, Object movement) {
            this.player = player;
            this.scale = scale;
            this.movement = movement;
        }

        public Object player() {
            return player;
        }

        public float scale() {
            return scale;
        }

        public void scale(float replacement) {
            scale = replacement;
        }

        public Object movement() {
            return movement;
        }

        public void movement(Object replacement) {
            if (replacement != null) movement = replacement;
        }
    }

    public record MouseMove(Object handler, Phase phase) {}

    public record PlayerMoveEnd(Object player) {}

    public record PlayerPosition(Object player, Phase phase) {}

    public record RenderState(Object entity, Object state, float partialTick) {}

    public record RendererClose(Object renderer) {}

    public static final class MethodHook {
        private final String id;
        private final Object owner;
        private final Object argument;
        private Object value;

        public MethodHook(String id, Object owner, Object argument, Object value) {
            this.id = id;
            this.owner = owner;
            this.argument = argument;
            this.value = value;
        }

        public String id() {
            return id;
        }

        public Object owner() {
            return owner;
        }

        public Object argument() {
            return argument;
        }

        public Object value() {
            return value;
        }

        public void value(Object replacement) {
            value = replacement;
        }
    }

    public enum Kind {
        ATTACK,
        USE
    }

    public enum PacketPhase {
        SEND_PRE,
        SEND_POST,
        RECEIVE_NETWORK,
        RECEIVE_APPLY
    }

    private final EventChannel<ClientTick> clientTick = new EventChannel<>();
    private final EventChannel<Frame> frame = new EventChannel<>();
    private final EventChannel<Hud> hud = new EventChannel<>();
    private final EventChannel<WorldRender> worldRender = new EventChannel<>();
    private final EventChannel<Key> key = new EventChannel<>();
    private final EventChannel<Mouse> mouse = new EventChannel<>();
    private final EventChannel<MouseScroll> mouseScroll = new EventChannel<>();
    private final EventChannel<Action> action = new EventChannel<>();
    private final EventChannel<Packet> packet = new EventChannel<>();
    private final EventChannel<BlockUpdate> blockUpdate = new EventChannel<>();
    private final EventChannel<PlayerUpdate> playerUpdate = new EventChannel<>();
    private final EventChannel<MoveInput> moveInput = new EventChannel<>();
    private final EventChannel<PlayerMove> playerMove = new EventChannel<>();
    private final EventChannel<MouseMove> mouseMove = new EventChannel<>();
    private final EventChannel<PlayerMoveEnd> playerMoveEnd = new EventChannel<>();
    private final EventChannel<PlayerPosition> playerPosition = new EventChannel<>();
    private final EventChannel<RenderState> renderState = new EventChannel<>();
    private final EventChannel<RendererClose> rendererClose = new EventChannel<>();
    private final EventChannel<MethodHook> methodHook = new EventChannel<>();

    public EventChannel<ClientTick> clientTick() {
        return clientTick;
    }

    public EventChannel<Frame> frame() {
        return frame;
    }

    public EventChannel<Hud> hud() {
        return hud;
    }

    public EventChannel<WorldRender> worldRender() {
        return worldRender;
    }

    public EventChannel<Key> key() {
        return key;
    }

    public EventChannel<Mouse> mouse() {
        return mouse;
    }

    public EventChannel<MouseScroll> mouseScroll() {
        return mouseScroll;
    }

    public EventChannel<Action> action() {
        return action;
    }

    public EventChannel<Packet> packet() {
        return packet;
    }

    public EventChannel<BlockUpdate> blockUpdate() {
        return blockUpdate;
    }

    public EventChannel<PlayerUpdate> playerUpdate() {
        return playerUpdate;
    }

    public EventChannel<MoveInput> moveInput() {
        return moveInput;
    }

    public EventChannel<PlayerMove> playerMove() {
        return playerMove;
    }

    public EventChannel<MouseMove> mouseMove() {
        return mouseMove;
    }

    public EventChannel<PlayerMoveEnd> playerMoveEnd() {
        return playerMoveEnd;
    }

    public EventChannel<PlayerPosition> playerPosition() {
        return playerPosition;
    }

    public EventChannel<RenderState> renderState() {
        return renderState;
    }

    public EventChannel<RendererClose> rendererClose() {
        return rendererClose;
    }

    public EventChannel<MethodHook> methodHook() {
        return methodHook;
    }
}
