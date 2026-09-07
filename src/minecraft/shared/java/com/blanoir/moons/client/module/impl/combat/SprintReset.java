package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** OpenExpo SprintReset: Legit W-tap and NoStop sprint packet reset modes. */
public final class SprintReset {
    private static final BooleanSetting ENABLED = bool("sprintreset.enabled", false);
    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("sprintreset.mode")
                    .defaultValue(Mode.NO_STOP)
                    .option(Mode.NO_STOP, "no_stop")
                    .option(Mode.LEGIT, "legit")
                    .build();
    private static final IntSetting INTERVAL_MS = integer("sprintreset.intervalMs", 400, 0, 2000);
    private static final BooleanSetting REQUIRE_TARGET_DAMAGE =
            bool("sprintreset.requireTargetDamage", true);
    private static final IntSetting DURATION_MS = integer("sprintreset.durationMs", 50, 0, 200);

    private static int pendingTargetId = -1;
    private static int pendingInitialHurtTime;
    private static int pendingTicks;
    private static long lastResetAtMs;
    private static long restoreAtMs;
    private static boolean resetActive;
    private static boolean resumeSprint;

    private SprintReset() {}

    public static void init() {
        EventBus.TICK.register("SprintReset.tick", event -> tick(event.client()));
    }

    /** Called immediately before vanilla sends the attack. */
    public static void onAttack(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (!ENABLED.get()
                || !(entity instanceof LivingEntity target)
                || currentPlayer == null
                || !currentPlayer.isSprinting()
                || System.currentTimeMillis() - lastResetAtMs < INTERVAL_MS.get()) return;

        if (REQUIRE_TARGET_DAMAGE.get()) {
            pendingTargetId = target.getId();
            pendingInitialHurtTime = target.hurtTime;
            pendingTicks = 10;
        } else {
            beginReset(client);
        }
    }

    private static void tick(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (client == null || currentPlayer == null || currentLevel == null || !ENABLED.get()) {
            clear(client);
            return;
        }

        if (pendingTargetId >= 0) {
            Entity entity = currentLevel.getEntity(pendingTargetId);
            if (entity instanceof LivingEntity target
                    && (target.hurtTime > pendingInitialHurtTime || target.hurtTime >= 9)) {
                pendingTargetId = -1;
                pendingTicks = 0;
                beginReset(client);
            } else if (--pendingTicks <= 0 || entity == null || !entity.isAlive()) {
                pendingTargetId = -1;
                pendingTicks = 0;
            }
        }

        if (resetActive && System.currentTimeMillis() >= restoreAtMs) {
            CombatInputController.releaseForward(client, CombatInputController.Owner.SPRINT_RESET);
            CombatInputController.releaseSprint(client, CombatInputController.Owner.SPRINT_RESET);
            if (resumeSprint && currentPlayer.input != null) currentPlayer.setSprinting(true);
            resetActive = false;
            resumeSprint = false;
        }
    }

    private static void beginReset(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer == null || resetActive) return;
        lastResetAtMs = System.currentTimeMillis();
        restoreAtMs = lastResetAtMs + DURATION_MS.get();
        resumeSprint = currentPlayer.isSprinting();
        CombatInputController.suppressSprint(client, CombatInputController.Owner.SPRINT_RESET);
        currentPlayer.setSprinting(false);
        if (MODE.get() == Mode.LEGIT) {
            CombatInputController.suppressForward(client, CombatInputController.Owner.SPRINT_RESET);
        }
        resetActive = true;
    }

    private static void clear(Minecraft client) {
        pendingTargetId = -1;
        pendingTicks = 0;
        if (client != null) {
            CombatInputController.releaseForward(client, CombatInputController.Owner.SPRINT_RESET);
            CombatInputController.releaseSprint(client, CombatInputController.Owner.SPRINT_RESET);
        }
        resetActive = false;
        resumeSprint = false;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) clear(client);
        ClientChat.send(client, "SprintReset " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static String hudTag() {
        return MODE.serialized();
    }

    public static int setMode(Minecraft ignoredClient, String value) {
        MODE.deserialize(value);
        return 1;
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static int setIntervalMs(Minecraft ignoredClient, int value) {
        INTERVAL_MS.set(value);
        return 1;
    }

    public static int setRequireTargetDamage(Minecraft ignoredClient, boolean value) {
        REQUIRE_TARGET_DAMAGE.set(value);
        return 1;
    }

    public static int setDurationMs(Minecraft ignoredClient, int value) {
        DURATION_MS.set(value);
        return 1;
    }

    private enum Mode {
        NO_STOP,
        LEGIT
    }

    private static BooleanSetting bool(String name, boolean value) {
        return new BooleanSetting.Builder().name(name).defaultValue(value).build();
    }

    private static IntSetting integer(String name, int value, int min, int max) {
        return new IntSetting.Builder().name(name).defaultValue(value).range(min, max).build();
    }
}
