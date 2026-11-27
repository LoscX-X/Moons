package com.blanoir.tempos.client.module.impl.combat;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Sprint reset with Legit W-tap and NoStop packet modes. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Resets sprint after an attack using the selected timing.")
public final class SprintReset extends ClientFeature {
    public static final SprintReset INSTANCE = new SprintReset();

    private final BooleanSetting enabled = bool("sprintreset.enabled", false);
    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("sprintreset.mode")
                    .defaultValue(Mode.NO_STOP)
                    .option(Mode.NO_STOP, "no_stop")
                    .option(Mode.LEGIT, "legit")
                    .build();
    private final IntSetting intervalMs = integer("sprintreset.intervalMs", 400, 2000);
    private final BooleanSetting requireTargetDamage =
            bool("sprintreset.requireTargetDamage", true);
    private final IntSetting durationMs = integer("sprintreset.durationMs", 50, 200);

    private int pendingTargetId = -1;
    private int pendingInitialHurtTime;
    private int pendingTicks;
    private long lastResetAtMs;
    private long restoreAtMs;
    private boolean resetActive;
    private boolean resumeSprint;

    private SprintReset() {
        super("sprintreset", "SprintReset");
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register("SprintReset.tick", event -> tick(event.client()));
    }

    /** Called immediately before vanilla sends the attack. */
    public void onAttack(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client.player;
        if (!this.enabled.get()
                || !(entity instanceof LivingEntity target)
                || currentPlayer == null
                || !currentPlayer.isSprinting()
                || System.currentTimeMillis() - this.lastResetAtMs < this.intervalMs.get()) return;

        if (this.requireTargetDamage.get()) {
            this.pendingTargetId = target.getId();
            this.pendingInitialHurtTime = target.hurtTime;
            this.pendingTicks = 10;
        } else {
            beginReset(client);
        }
    }

    private void tick(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (client == null
                || currentPlayer == null
                || currentLevel == null
                || !this.enabled.get()) {
            clear(client);
            return;
        }

        if (this.pendingTargetId >= 0) {
            Entity entity = currentLevel.getEntity(this.pendingTargetId);
            if (entity instanceof LivingEntity target
                    && (target.hurtTime > this.pendingInitialHurtTime || target.hurtTime >= 9)) {
                this.pendingTargetId = -1;
                this.pendingTicks = 0;
                beginReset(client);
            } else if (--this.pendingTicks <= 0 || entity == null || !entity.isAlive()) {
                this.pendingTargetId = -1;
                this.pendingTicks = 0;
            }
        }

        if (this.resetActive && System.currentTimeMillis() >= this.restoreAtMs) {
            CombatInputController.releaseForward(client, CombatInputController.Owner.SPRINT_RESET);
            CombatInputController.releaseSprint(client, CombatInputController.Owner.SPRINT_RESET);
            if (this.resumeSprint) currentPlayer.setSprinting(true);
            this.resetActive = false;
            this.resumeSprint = false;
        }
    }

    private void beginReset(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer == null || this.resetActive) return;
        this.lastResetAtMs = System.currentTimeMillis();
        this.restoreAtMs = this.lastResetAtMs + this.durationMs.get();
        this.resumeSprint = currentPlayer.isSprinting();
        CombatInputController.suppressSprint(client, CombatInputController.Owner.SPRINT_RESET);
        currentPlayer.setSprinting(false);
        if (this.mode.get() == Mode.LEGIT) {
            CombatInputController.suppressForward(client, CombatInputController.Owner.SPRINT_RESET);
        }
        this.resetActive = true;
    }

    private void clear(Minecraft client) {
        this.pendingTargetId = -1;
        this.pendingTicks = 0;
        if (client != null) {
            CombatInputController.releaseForward(client, CombatInputController.Owner.SPRINT_RESET);
            CombatInputController.releaseSprint(client, CombatInputController.Owner.SPRINT_RESET);
        }
        this.resetActive = false;
        this.resumeSprint = false;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        if (!value) clear(client);
        ClientChat.send(client, "SprintReset " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public String hudTag() {
        return this.mode.serialized();
    }

    public int setMode(Minecraft ignoredClient, String value) {
        this.mode.deserialize(value);
        return 1;
    }

    public int setIntervalMs(Minecraft ignoredClient, int value) {
        this.intervalMs.set(value);
        return 1;
    }

    public int setRequireTargetDamage(Minecraft ignoredClient, boolean value) {
        this.requireTargetDamage.set(value);
        return 1;
    }

    public int setDurationMs(Minecraft ignoredClient, int value) {
        this.durationMs.set(value);
        return 1;
    }

    private enum Mode {
        NO_STOP,
        LEGIT
    }

    private static BooleanSetting bool(String name, boolean value) {
        return new BooleanSetting.Builder().name(name).defaultValue(value).build();
    }

    private static IntSetting integer(String name, int value, int max) {
        return new IntSetting.Builder().name(name).defaultValue(value).range(0, max).build();
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            mode.describeStored("mode", "Mode", this::setMode),
            intervalMs.describeStored("interval", "Interval ms", 1, this::setIntervalMs),
            requireTargetDamage.describeStored(
                    "require_damage", "Require target damage", this::setRequireTargetDamage),
            durationMs.describeStored("duration", "Duration ms", 1, this::setDurationMs)
        };
    }
}
