package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.impl.combat.hitselect.HitSelectCycle;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Bounded attack timing for manual clicks, AutoClicker and SilentAura. TriggerBot opts out. */
public final class HitSelect {
    private static final BooleanSetting ENABLED = bool("enabled", false);
    private static final BooleanSetting SERVER_TIME = bool("serverTime", false);
    private static final IntSetting PAUSE = number("pauseMs", 450, 500);
    private static final IntSetting FIRST = number("firstMs", 150, 500);
    private static final IntSetting TRADE = number("tradeMs", 0, 250);
    private static final Map<Entity, Track> TRACKS = new IdentityHashMap<>();
    private static ClientLevel level;
    private static Player player;
    private static int bypassDepth;
    private static String status = "Ready";

    private record Track(HitSelectCycle cycle, long touched) {}

    private HitSelect() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("HitSelect.context", event -> reset());
        EventBus.TICK.register(
                "HitSelect.expire",
                event -> {
                    if (!context(event.client())) return;
                    long now = now();
                    TRACKS.entrySet()
                            .removeIf(
                                    e ->
                                            !eligible(event.client(), e.getKey())
                                                    || now - e.getValue().touched() > 3000);
                    if (TRACKS.isEmpty()) status = "Ready";
                });
        EventBus.ATTACK_INPUT_PRE.register(
                "HitSelect.input",
                EventPriority.HIGHEST,
                event -> {
                    if (shouldDelay(
                            event.client(),
                            CombatInputController.attackInputTarget(event.client())))
                        event.cancel();
                });
        EventBus.ATTACK_ENTITY_POST.register(
                "HitSelect.dispatched",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (bypassDepth == 0
                            && context(client)
                            && event.attacker() == player
                            && eligible(client, event.target())) {
                        track(event.target()).attacked(now());
                        status = "Pause";
                    }
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "HitSelect.damage",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!context(client)
                            || event.listener() != client.getConnection()
                            || !(event.packet() instanceof ClientboundDamageEventPacket damage))
                        return;
                    long now = now();
                    Entity target = level.getEntity(damage.entityId());
                    if (eligible(client, target)) {
                        track(target)
                                .targetDamaged(
                                        now,
                                        damage.sourceCauseId() == player.getId()
                                                && damage.sourceDirectId() == player.getId());
                    } else if (target == player) {
                        Entity attacker = level.getEntity(damage.sourceCauseId());
                        if (eligible(client, attacker)
                                && damage.sourceDirectId() == attacker.getId())
                            track(attacker).incomingHit(now);
                    }
                });
    }

    public static boolean shouldDelay(Minecraft client, Entity target) {
        if (bypassDepth > 0 || !context(client) || !eligible(client, target)) return false;
        var info =
                client.getConnection() == null
                        ? null
                        : client.getConnection().getPlayerInfo(player.getUUID());
        var gate =
                track(target)
                        .evaluate(
                                now(),
                                new HitSelectCycle.Policy(
                                        PAUSE.get(),
                                        FIRST.get(),
                                        TRADE.get(),
                                        SERVER_TIME.get(),
                                        info == null ? 0 : Math.clamp(info.getLatency(), 0, 2000)));
        status =
                switch (gate) {
                    case READY -> "Ready";
                    case FIRST_HIT -> "First hit";
                    case REPEAT -> "Pause";
                    case TRADE -> "Trade";
                };
        return gate != HitSelectCycle.Gate.READY;
    }

    /** Scoped opt-out survives nested vanilla input hooks and always restores on failure. */
    public static boolean withoutFiltering(BooleanSupplier action) {
        bypassDepth++;
        try {
            return action.getAsBoolean();
        } finally {
            bypassDepth--;
        }
    }

    private static HitSelectCycle track(Entity target) {
        Track old = TRACKS.get(target);
        if (old == null && TRACKS.size() >= 64) {
            Entity oldest =
                    TRACKS.entrySet().stream()
                            .min(java.util.Comparator.comparingLong(e -> e.getValue().touched()))
                            .orElseThrow()
                            .getKey();
            TRACKS.remove(oldest);
        }
        var cycle = old == null ? new HitSelectCycle() : old.cycle();
        TRACKS.put(target, new Track(cycle, now()));
        return cycle;
    }

    private static boolean eligible(Minecraft client, Entity entity) {
        return entity != null
                && Targeting.isEnemyPlayer(client, entity)
                && client.level.getEntity(entity.getId()) == entity;
    }

    private static boolean context(Minecraft client) {
        if (!ENABLED.get() || !ClientReady.aliveGameplay(client) || client.player.isSpectator()) {
            reset();
            return false;
        }
        if (level != client.level || player != client.player) {
            reset();
            level = client.level;
            player = client.player;
        }
        return true;
    }

    private static void reset() {
        TRACKS.clear();
        level = null;
        player = null;
        status = "Ready";
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusTag() {
        return status;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        reset();
        ENABLED.set(value);
        ClientChat.send(client, "HitSelect " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setPause(Minecraft client, int value) {
        PAUSE.set(value);
        reset();
        return 1;
    }

    public static int setFirst(Minecraft client, int value) {
        FIRST.set(value);
        reset();
        return 1;
    }

    public static int setTrade(Minecraft client, int value) {
        TRADE.set(value);
        reset();
        return 1;
    }

    public static int setServerTime(Minecraft client, boolean value) {
        SERVER_TIME.set(value);
        reset();
        return 1;
    }

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder().name("hitselect." + key).defaultValue(value).build();
    }

    private static IntSetting number(String key, int value, int max) {
        return new IntSetting.Builder()
                .name("hitselect." + key)
                .defaultValue(value)
                .range(0, max)
                .build();
    }
}
