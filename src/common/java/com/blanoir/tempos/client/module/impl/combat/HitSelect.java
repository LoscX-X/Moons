package com.blanoir.tempos.client.module.impl.combat;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;
import static com.blanoir.tempos.client.module.framework.SettingFactory.number;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.blanoir.tempos.client.module.impl.combat.hitselect.HitSelectCycle;
import com.blanoir.tempos.client.lifecycle.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Bounded attack timing for manual clicks, AutoClicker and SilentAura. TriggerBot opts out. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Times repeated attacks using the delay and damage-confirmation settings.")
public final class HitSelect extends ClientFeature {
    public static final HitSelect INSTANCE = new HitSelect();

    private final BooleanSetting enabled = bool("enabled", false);
    private final BooleanSetting serverTime = bool("serverTime", false);
    private final IntSetting pause = number("pauseMs", 450, 500);
    private final IntSetting first = number("firstMs", 150, 500);
    private final IntSetting trade = number("tradeMs", 0, 250);
    private final Map<Entity, Track> tracks = new IdentityHashMap<>();
    private ClientLevel level;
    private Player player;
    private int bypassDepth;
    private String status = "Ready";

    private record Track(HitSelectCycle cycle, long touched) {}

    private HitSelect() {
        super("hitselect", "HitSelect");
    }

    @Override
    public void registerEvents() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("HitSelect.context", event -> reset());
        EventBus.TICK.register(
                "HitSelect.expire",
                event -> {
                    if (!context(event.client())) return;
                    long now = now();
                    this.tracks
                            .entrySet()
                            .removeIf(
                                    e ->
                                            !eligible(event.client(), e.getKey())
                                                    || now - e.getValue().touched() > 3000);
                    if (this.tracks.isEmpty()) this.status = "Ready";
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
                    if (this.bypassDepth == 0
                            && context(client)
                            && event.attacker() == this.player
                            && eligible(client, event.target())) {
                        track(event.target()).attacked(now());
                        this.status = "Pause";
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
                    Entity target = this.level.getEntity(damage.entityId());
                    if (eligible(client, target)) {
                        track(target)
                                .targetDamaged(
                                        now,
                                        damage.sourceCauseId() == this.player.getId()
                                                && damage.sourceDirectId() == this.player.getId());
                    } else if (target == this.player) {
                        Entity attacker = this.level.getEntity(damage.sourceCauseId());
                        if (eligible(client, attacker)
                                && damage.sourceDirectId() == attacker.getId())
                            track(attacker).incomingHit(now);
                    }
                });
    }

    public boolean shouldDelay(Minecraft client, Entity target) {
        if (this.bypassDepth > 0 || !context(client) || !eligible(client, target)) return false;
        var info =
                client.getConnection() == null
                        ? null
                        : client.getConnection().getPlayerInfo(this.player.getUUID());
        var gate =
                track(target)
                        .evaluate(
                                now(),
                                new HitSelectCycle.Policy(
                                        this.pause.get(),
                                        this.first.get(),
                                        this.trade.get(),
                                        this.serverTime.get(),
                                        info == null ? 0 : Math.clamp(info.getLatency(), 0, 2000)));
        this.status =
                switch (gate) {
                    case READY -> "Ready";
                    case FIRST_HIT -> "First hit";
                    case REPEAT -> "Pause";
                    case TRADE -> "Trade";
                };
        return gate != HitSelectCycle.Gate.READY;
    }

    /** Scoped opt-out survives nested vanilla input hooks and always restores on failure. */
    public boolean withoutFiltering(BooleanSupplier action) {
        this.bypassDepth++;
        try {
            return action.getAsBoolean();
        } finally {
            this.bypassDepth--;
        }
    }

    private HitSelectCycle track(Entity target) {
        Track old = this.tracks.get(target);
        if (old == null && this.tracks.size() >= 64) {
            Entity oldest =
                    this.tracks.entrySet().stream()
                            .min(java.util.Comparator.comparingLong(e -> e.getValue().touched()))
                            .orElseThrow()
                            .getKey();
            this.tracks.remove(oldest);
        }
        var cycle = old == null ? new HitSelectCycle() : old.cycle();
        this.tracks.put(target, new Track(cycle, now()));
        return cycle;
    }

    private static boolean eligible(Minecraft client, Entity entity) {
        return entity != null
                && Targeting.isEnemyPlayer(client, entity)
                && client.level.getEntity(entity.getId()) == entity;
    }

    private boolean context(Minecraft client) {
        if (!this.enabled.get()
                || !ClientReady.aliveGameplay(client)
                || client.player.isSpectator()) {
            reset();
            return false;
        }
        if (this.level != client.level || this.player != client.player) {
            reset();
            this.level = client.level;
            this.player = client.player;
        }
        return true;
    }

    private void reset() {
        this.tracks.clear();
        this.level = null;
        this.player = null;
        this.status = "Ready";
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public String statusTag() {
        return this.status;
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        reset();
        this.enabled.set(value);
        ClientChat.send(client, "HitSelect " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setPause(Minecraft client, int value) {
        this.pause.set(value);
        reset();
        return 1;
    }

    public int setFirst(Minecraft client, int value) {
        this.first.set(value);
        reset();
        return 1;
    }

    public int setTrade(Minecraft client, int value) {
        this.trade.set(value);
        reset();
        return 1;
    }

    public int setServerTime(Minecraft client, boolean value) {
        this.serverTime.set(value);
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

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            integer(
                    "pause",
                    "Repeat pause (ms)",
                    "hitselect.pauseMs",
                    450,
                    0,
                    500,
                    10,
                    this::setPause),
            integer(
                    "first",
                    "Wait for first hit (ms)",
                    "hitselect.firstMs",
                    150,
                    0,
                    500,
                    10,
                    this::setFirst),
            integer(
                    "trade",
                    "Wait in trades (ms)",
                    "hitselect.tradeMs",
                    0,
                    0,
                    250,
                    10,
                    this::setTrade),
            SettingFactory.bool(
                    "servertime",
                    "Use damage confirmation",
                    "hitselect.serverTime",
                    false,
                    this::setServerTime)
        };
    }
}
