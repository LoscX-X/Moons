package com.blanoir.moons.client.event;

import com.blanoir.moons.client.event.action.AttackInputEvent;
import com.blanoir.moons.client.event.action.UseInputEvent;
import com.blanoir.moons.client.event.combat.AttackEntityEvent;
import com.blanoir.moons.client.event.combat.BlockBreakEvent;
import com.blanoir.moons.client.event.combat.PickResultEvent;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.HudRenderEvent;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.event.input.KeyInputEvent;
import com.blanoir.moons.client.event.input.MouseButtonEvent;
import com.blanoir.moons.client.event.input.MouseMotionEvent;
import com.blanoir.moons.client.event.input.MouseScrollEvent;
import com.blanoir.moons.client.event.lifecycle.ClientContextChangedEvent;
import com.blanoir.moons.client.event.movement.LocalPlayerLivingTickEvent;
import com.blanoir.moons.client.event.movement.MoveInputEvent;
import com.blanoir.moons.client.event.movement.MovementInputUpdatedEvent;
import com.blanoir.moons.client.event.movement.PlayerMotionEvent;
import com.blanoir.moons.client.event.movement.PlayerMoveEndEvent;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.event.movement.StrafeEvent;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.event.render.EntityRenderStateEvent;
import com.blanoir.moons.client.event.render.LivingRenderEvent;
import com.blanoir.moons.client.event.render.RendererCloseEvent;
import com.blanoir.moons.client.event.tick.TickEndEvent;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.event.world.BlockUpdateEvent;

/** Typed feature boundaries published synchronously at the existing runtime hooks. */
public final class EventBus {
    // Client lifecycle and frame boundaries.
    public static final Event<TickEvent> TICK =
            new Event<>("client.tick.start", EventThread.CLIENT);
    public static final Event<TickEndEvent> TICK_END =
            new Event<>("client.tick.end", EventThread.CLIENT);
    public static final Event<ClientContextChangedEvent> CLIENT_CONTEXT_CHANGED =
            new Event<>("client.context.changed", EventThread.CLIENT);
    public static final Event<FrameEvent> FRAME = new Event<>("client.frame", EventThread.RENDER);
    public static final Event<HudRenderEvent> HUD_RENDER =
            new Event<>("render.hud", EventThread.RENDER);
    public static final Event<WorldRenderEvent> WORLD_RENDER =
            new Event<>("render.world", EventThread.RENDER);

    // Raw input and high-level client actions.
    public static final Event<KeyInputEvent> KEY_INPUT =
            new Event<>("input.key", EventThread.CLIENT);
    public static final Event<MouseButtonEvent> MOUSE_BUTTON =
            new Event<>("input.mouse.button", EventThread.CLIENT);
    public static final Event<MouseScrollEvent> MOUSE_SCROLL =
            new Event<>("input.mouse.scroll", EventThread.CLIENT);
    public static final Event<MouseMotionEvent.Pre> MOUSE_MOTION_PRE =
            new Event<>("input.mouse.motion.pre", EventThread.CLIENT);
    public static final Event<MouseMotionEvent.Post> MOUSE_MOTION_POST =
            new Event<>("input.mouse.motion.post", EventThread.CLIENT);
    public static final Event<AttackInputEvent.Pre> ATTACK_INPUT_PRE =
            new Event<>("action.attack.pre", EventThread.CLIENT);
    public static final Event<AttackInputEvent.Post> ATTACK_INPUT_POST =
            new Event<>("action.attack.post", EventThread.CLIENT);
    public static final Event<UseInputEvent.Pre> USE_INPUT_PRE =
            new Event<>("action.use.pre", EventThread.CLIENT);
    public static final Event<UseInputEvent.Post> USE_INPUT_POST =
            new Event<>("action.use.post", EventThread.CLIENT);

    // Local-player movement. PLAYER_UPDATE remains the cancellable tick-head boundary.
    public static final Event<StrafeEvent> STRAFE =
            new Event<>("movement.strafe", EventThread.CLIENT);
    public static final Event<MoveInputEvent> MOVE_INPUT =
            new Event<>("input.move", EventThread.CLIENT);
    public static final Event<MovementInputUpdatedEvent> MOVEMENT_INPUT_UPDATED =
            new Event<>("input.move.updated", EventThread.CLIENT);
    public static final Event<PlayerUpdateEvent> PLAYER_UPDATE =
            new Event<>("player.update", EventThread.CLIENT);
    public static final Event<LocalPlayerLivingTickEvent> LOCAL_PLAYER_LIVING_TICK =
            new Event<>("player.living-tick", EventThread.CLIENT);
    public static final Event<PlayerMoveEndEvent> PLAYER_MOVE_END =
            new Event<>("player.move.end", EventThread.CLIENT);
    public static final Event<PlayerMotionEvent.Pre> PLAYER_MOTION_PRE =
            new Event<>("player.motion.pre", EventThread.CLIENT);
    public static final Event<PlayerMotionEvent.Post> PLAYER_MOTION_POST =
            new Event<>("player.motion.post", EventThread.CLIENT);

    // Packet PRE is cancellable; POST is a completion boundary, not proof of wire delivery.
    public static final Event<PacketSendEvent.Pre> PACKET_SEND_PRE =
            new Event<>("packet.send.pre", EventThread.CALLER);
    public static final Event<PacketSendEvent.Post> PACKET_SEND_POST =
            new Event<>("packet.send.post", EventThread.CALLER);
    public static final Event<PacketReceiveEvent.Pre> PACKET_RECEIVE_PRE =
            new Event<>("packet.receive.pre", EventThread.NETWORK);
    public static final Event<PacketReceiveEvent.Bundle> PACKET_RECEIVE_BUNDLE =
            new Event<>("packet.receive.bundle", EventThread.NETWORK);
    public static final Event<PacketReceiveEvent.Apply> PACKET_RECEIVE_APPLY =
            new Event<>("packet.receive.apply", EventThread.CLIENT);

    // World and combat boundaries already supplied by the runtime transformer.
    public static final Event<BlockUpdateEvent.Pre> BLOCK_UPDATE_PRE =
            new Event<>("world.block-update.pre", EventThread.CLIENT);
    public static final Event<BlockUpdateEvent.Post> BLOCK_UPDATE_POST =
            new Event<>("world.block-update.post", EventThread.CLIENT);
    public static final Event<BlockBreakEvent> BLOCK_BREAK_PRE =
            new Event<>("combat.block-break.pre", EventThread.CLIENT);
    public static final Event<AttackEntityEvent.Pre> ATTACK_ENTITY_PRE =
            new Event<>("combat.attack-entity.pre", EventThread.CLIENT);
    public static final Event<AttackEntityEvent.Post> ATTACK_ENTITY_POST =
            new Event<>("combat.attack-entity.post", EventThread.CLIENT);
    public static final Event<PickResultEvent> PICK_RESULT =
            new Event<>("combat.pick-result", EventThread.CLIENT);

    // Render extraction, submit and resource-lifecycle boundaries.
    public static final Event<EntityRenderStateEvent> ENTITY_RENDER_STATE =
            new Event<>("render.entity-state", EventThread.RENDER);
    public static final Event<LivingRenderEvent.Pre> LIVING_RENDER_PRE =
            new Event<>("render.living.pre", EventThread.RENDER);
    public static final Event<LivingRenderEvent.Post> LIVING_RENDER_POST =
            new Event<>("render.living.post", EventThread.RENDER);
    public static final Event<RendererCloseEvent> RENDERER_CLOSE =
            new Event<>("render.close", EventThread.RENDER);

    private EventBus() {}
}
