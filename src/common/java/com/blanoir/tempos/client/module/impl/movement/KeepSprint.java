package com.blanoir.tempos.client.module.impl.movement;


import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.combat.AttackSlowdownTracker;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.math.RandomChance;
import com.blanoir.tempos.client.utils.math.RandomRange;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Attack slowdown control. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Controls how much horizontal speed you retain after an attack.")
public final class KeepSprint extends ClientFeature {
    public static final KeepSprint INSTANCE = new KeepSprint();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("keepsprint.enabled").defaultValue(false).build();

    private final DoubleSetting motionMin =
            new DoubleSetting.Builder()
                    .name("keepsprint.motion.min")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private final DoubleSetting motionMax =
            new DoubleSetting.Builder()
                    .name("keepsprint.motion.max")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private final DoubleSetting hurtMotionMin =
            new DoubleSetting.Builder()
                    .name("keepsprint.hurtMotion.min")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private final DoubleSetting hurtMotionMax =
            new DoubleSetting.Builder()
                    .name("keepsprint.hurtMotion.max")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private final IntSetting hurtTimeMin =
            new IntSetting.Builder()
                    .name("keepsprint.hurtTime.min")
                    .defaultValue(1)
                    .range(1, 10)
                    .build();

    private final IntSetting hurtTimeMax =
            new IntSetting.Builder()
                    .name("keepsprint.hurtTime.max")
                    .defaultValue(10)
                    .range(1, 10)
                    .build();

    private final DoubleSetting chance =
            new DoubleSetting.Builder()
                    .name("keepsprint.chance")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private boolean sprinting;

    private KeepSprint() {
        super("keepsprint", "KeepSprint");
    }

    @Override
    public void registerEvents() {
        EventBus.TICK_END.register(
                "KeepSprint.tickEnd",
                event -> {
                    Minecraft client = event.client();
                    if (client.player != null) {
                        this.sprinting = client.player.isSprinting();
                    }
                });
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public double attackMotionMultiplier() {
        if (!this.enabled.get() || !RandomChance.chancePercent(this.chance.get())) {
            return 0.6D;
        }

        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        int hurtTime = currentPlayer == null ? 0 : currentPlayer.hurtTime;
        boolean hurt = hurtTime >= this.hurtTimeMin.get() && hurtTime <= this.hurtTimeMax.get();
        double min = hurt ? this.hurtMotionMin.get() : this.motionMin.get();
        double max = hurt ? this.hurtMotionMax.get() : this.motionMax.get();
        return RandomRange.between(min, max) / 100.0D;
    }

    /** Called immediately around vanilla Player.causeExtraKnockback. */
    public void beginAttackSlowdown(Object owner) {
        Minecraft client = Minecraft.getInstance();
        if (this.enabled.get() && owner instanceof Player player && client.player == player) {
            AttackSlowdownTracker.capture(player);
        }
    }

    /** Replaces vanilla's 0.6 horizontal multiplier and sprint reset. */
    public void finishAttackSlowdown(Object owner) {
        if (!(owner instanceof Player player)) return;
        if (!this.enabled.get()) {
            AttackSlowdownTracker.discard(player);
            return;
        }
        // Consume the remembered sprint state after the first
        // slowdown in a tick. Later attack calls are restored with 1.0 so the
        // same vanilla 0.6 slowdown cannot be compounded.
        double multiplier = this.sprinting ? attackMotionMultiplier() : 1.0D;
        if (AttackSlowdownTracker.replaceVanillaSlowdown(player, multiplier)) {
            this.sprinting = false;
        }
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        ClientChat.send(client, "KeepSprint " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setMotion(Minecraft ignoredClient, double min, double max) {
        this.motionMin.set(Math.min(min, max));
        this.motionMax.set(Math.max(min, max));
        return 1;
    }

    public int setHurtMotion(Minecraft ignoredClient, double min, double max) {
        this.hurtMotionMin.set(Math.min(min, max));
        this.hurtMotionMax.set(Math.max(min, max));
        return 1;
    }

    public int setHurtTime(Minecraft ignoredClient, int min, int max) {
        this.hurtTimeMin.set(Math.min(min, max));
        this.hurtTimeMax.set(Math.max(min, max));
        return 1;
    }

    public int setChance(Minecraft ignoredClient, double value) {
        this.chance.set(value);
        return 1;
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            motionMin.describeStoredRange("motion", "Motion %", motionMax, 1, this::setMotion),
            hurtMotionMin.describeStoredRange(
                    "hurt_motion", "Motion when hurt %", hurtMotionMax, 1, this::setHurtMotion),
            hurtTimeMin.describeStoredRange(
                    "hurt_time", "Hurt time", hurtTimeMax, 1, this::setHurtTime),
            chance.describeStored("chance", "Chance %", 1, this::setChance)
        };
    }
}
