package com.blanoir.tempos.client.module.impl.combat.critical;

import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.StringSetting;
import com.blanoir.tempos.client.manager.combat.CombatDecisionEngine;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.combat.critical.mode.CriticalMode;
import com.blanoir.tempos.client.module.impl.combat.critical.mode.PacketCritical;
import com.blanoir.tempos.client.module.impl.combat.critical.mode.PacketMode;
import com.blanoir.tempos.client.module.impl.combat.critical.mode.Predict;
import com.blanoir.tempos.client.module.impl.combat.critical.mode.PredictMode;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Root "Critical" feature. It owns the active {@code critical.mode} selection and
 * fans every combat decision out to the currently selected {@link CriticalMode}.
 * New critical strategies register here as additional modes (see register).
 */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Selects the critical-hit mode and its timing settings.")
public final class Critical extends ClientFeature {
    public static final Critical INSTANCE = new Critical();

    private int silentAuraBypassDepth;

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

    private final StringSetting mode =
            new StringSetting.Builder().name("critical.mode").defaultValue("predict").build();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder()
                    .name("predictcritical.enabled")
                    .defaultValue(false)
                    .build();
    private final Map<String, CriticalMode> modes = new LinkedHashMap<>();
    private final Predict predict = sub(new Predict(this));

    private Critical() {
        super("critical", "Critical");
        register(new PredictMode(predict));
        register(new PacketMode(sub(new PacketCritical(this))));
    }

    public boolean configuredEnabled() {
        return enabled.get();
    }

    public void setConfiguredEnabled(boolean active) {
        enabled.set(active);
    }

    @Override
    protected String statusTag() {
        return modeName();
    }

    @Override
    protected Setting[] defineSettings() {
        var settings = new ArrayList<Setting>();
        settings.add(mode.describeChoice("mode", "Mode", modeIds(), this::setMode));
        predict.addSettingsTo(settings);
        return settings.toArray(Setting[]::new);
    }

    public void register(CriticalMode mode) {
        modes.putIfAbsent(mode.id(), mode);
    }

    public CriticalMode active() {
        String id = mode.get().toLowerCase(Locale.ROOT);
        return modes.getOrDefault(id, modes.get("predict"));
    }

    @Override
    public void registerEvents() {
        for (CriticalMode mode : modes.values()) {
            mode.initialize();
        }
    }

    public List<String> modeIds() {
        return List.copyOf(modes.keySet());
    }

    public String modeName() {
        return active().displayName();
    }

    @Override
    public boolean isEnabled() {
        return active().isEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return active().setEnabled(client, value);
    }

    public int setMode(Minecraft client, String id) {
        String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (!modes.containsKey(normalized)) {
            return active().showStatus(client);
        }
        CriticalMode previous = active();
        previous.cancelSilentAuraPrediction(client);
        mode.set(normalized);
        active().cancelSilentAuraPrediction(client);
        return active().showStatus(client);
    }

    public boolean predictMode() {
        return "predict".equals(mode.get().toLowerCase(Locale.ROOT));
    }

    /** Called from the real Minecraft attack hook immediately before dispatch. */
    public void beforeAttack(Minecraft client, Entity target) {
        if (silentAuraBypassDepth > 0) return;
        active().beforeAttack(client, target);
    }

    /**
     * Scopes one synchronous SilentAura dispatch outside Critical without
     * changing manual clicks or ordinary TriggerBot attacks.
     */
    public boolean withoutSilentAuraCritical(BooleanSupplier attack) {
        silentAuraBypassDepth++;
        try {
            return attack.getAsBoolean();
        } finally {
            silentAuraBypassDepth--;
        }
    }

    public AttackDecision requestAttack(
            Minecraft client, Entity target, int earliestAttackTick, boolean throughBlock) {
        return active().requestAttack(client, target, earliestAttackTick, throughBlock);
    }

    public AutomaticAttackGate gateSilentAuraAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        return active().gateSilentAuraAttack(client, target, Math.max(0, earliestAttackTick));
    }

    /** Generic automatic-attacker gate used by TriggerBot's camera and silent rays. */
    public AutomaticAttackGate gateAutomaticAttack(
            Minecraft client, Entity target, int earliestAttackTick) {
        return gateSilentAuraAttack(client, target, earliestAttackTick);
    }

    public void cancelSilentAuraPrediction(Minecraft client) {
        active().cancelSilentAuraPrediction(client);
    }

    public void cancelAutomaticPrediction(Minecraft client) {
        cancelSilentAuraPrediction(client);
    }

    public boolean isAimingWindowActive() {
        return active().isAimingWindowActive();
    }

    public CombatDecisionEngine.Decision getPlannedDecision() {
        return active().plannedDecision();
    }

    public int showStatus(Minecraft client) {
        return active().showStatus(client);
    }
}
