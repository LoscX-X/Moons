package com.blanoir.moons.client.module.impl.combat.critical;

import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.management.combat.CombatDecisionEngine;
import com.blanoir.moons.client.module.impl.combat.critical.mode.CriticalMode;
import com.blanoir.moons.client.module.impl.combat.critical.mode.PacketMode;
import com.blanoir.moons.client.module.impl.combat.critical.mode.PredictMode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.Map;

/**
 * Root "Critical" feature. It owns the active {@code critical.mode} selection and
 * fans every combat decision out to the currently selected {@link CriticalMode}.
 * New critical strategies register here as additional modes (see register).
 */
public final class Critical {
    private static int silentAuraBypassDepth;
    public enum AttackDecision {
        NONE,
        DEFERRED,
        ATTACKED,
        ABORTED
    }

    public enum AutomaticAttackGate {
        ALLOW,
        /** Old Predict ATTACKED: dispatch on this tick through the caller's final ray. */
        ATTACK,
        WAIT,
        BLOCK
    }

    private static final StringSetting MODE =
            new StringSetting.Builder()
                    .name("critical.mode")
                    .defaultValue("predict")
                    .build();

    private static final Map<String, CriticalMode> MODES = new LinkedHashMap<>();

    static {
        register(PredictMode.mode());
        register(PacketMode.mode());
    }

    private Critical() {
    }

    public static void register(CriticalMode mode) {
        MODES.putIfAbsent(mode.id(), mode);
    }

    public static CriticalMode active() {
        String id = MODE.get().toLowerCase(Locale.ROOT);
        return MODES.getOrDefault(id, MODES.get("predict"));
    }

    public static void init() {
        for (CriticalMode mode : MODES.values()) {
            mode.initialize();
        }
    }

    public static List<String> modeIds() {
        return List.copyOf(MODES.keySet());
    }

    public static String modeName() {
        return active().displayName();
    }

    public static boolean isEnabled() {
        return active().isEnabled();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        return active().setEnabled(client, value);
    }

    public static int setMode(Minecraft client, String id) {
        String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (!MODES.containsKey(normalized)) {
            return active().showStatus(client);
        }
        CriticalMode previous = active();
        previous.cancelSilentAuraPrediction(client);
        MODE.set(normalized);
        active().cancelSilentAuraPrediction(client);
        return active().showStatus(client);
    }

    public static boolean predictMode() {
        return "predict".equals(MODE.get().toLowerCase(Locale.ROOT));
    }

    /** Called from the real Minecraft attack hook immediately before dispatch. */
    public static void beforeAttack(Minecraft client, Entity target) {
        if (silentAuraBypassDepth > 0) return;
        active().beforeAttack(client, target);
    }

    /**
     * Scopes one synchronous SilentAura dispatch outside Critical without
     * changing manual clicks or ordinary TriggerBot attacks.
     */
    public static boolean withoutSilentAuraCritical(BooleanSupplier attack) {
        silentAuraBypassDepth++;
        try {
            return attack.getAsBoolean();
        } finally {
            silentAuraBypassDepth--;
        }
    }

    public static AttackDecision requestAttack(
            Minecraft client,
            Entity target,
            int earliestAttackTick,
            boolean throughBlock
    ) {
        return active().requestAttack(client, target, earliestAttackTick, throughBlock);
    }

    public static AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client,
            Entity target,
            int earliestAttackTick
    ) {
        return active().gateSilentAuraAttack(
                client, target, Math.max(0, earliestAttackTick));
    }

    /** Generic automatic-attacker gate used by TriggerBot's camera and silent rays. */
    public static AutomaticAttackGate gateAutomaticAttack(
            Minecraft client,
            Entity target,
            int earliestAttackTick
    ) {
        return gateSilentAuraAttack(client, target, earliestAttackTick);
    }

    public static void cancelSilentAuraPrediction(Minecraft client) {
        active().cancelSilentAuraPrediction(client);
    }

    public static void cancelAutomaticPrediction(Minecraft client) {
        cancelSilentAuraPrediction(client);
    }

    public static boolean isAimingWindowActive() {
        return active().isAimingWindowActive();
    }

    public static CombatDecisionEngine.Decision getPlannedDecision() {
        return active().plannedDecision();
    }

    public static int showStatus(Minecraft client) {
        return active().showStatus(client);
    }
}
