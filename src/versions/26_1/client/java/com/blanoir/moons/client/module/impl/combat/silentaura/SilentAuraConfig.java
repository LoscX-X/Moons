package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.management.targeting.Targeting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Settings only. Runtime state deliberately lives outside this class. */
public final class SilentAuraConfig {
    private static final BooleanSetting ENABLED = bool("silentaura.enabled", false);
    private static final DoubleSetting RANGE = decimal("silentaura.range", 3.7D, 1.0D, 6.0D);
    private static final DoubleSetting SCAN_EXTRA = decimal("silentaura.scanExtra", 2.5D, 0.0D, 7.0D);
    private static final DoubleSetting FOV = decimal("silentaura.fov", 180.0D, 1.0D, 360.0D);
    private static final ModeSetting<TargetMode> TARGET_MODE = new ModeSetting.Builder<TargetMode>()
            .name("silentaura.targetMode").defaultValue(TargetMode.SWITCH)
            .option(TargetMode.SWITCH, "switch")
            .option(TargetMode.SINGLE, "single").build();
    private static final IntSetting HURT_TIME = integer("silentaura.hurtTime", 10, 0, 10);
    private static final DoubleSetting SMOOTH = decimal("silentaura.smooth", 0.58D, 0.05D, 1.0D);
    private static final IntSetting FULL_LOCK_ANGLE_STEP =
            integer("silentaura.fullLock.angleStep", 90, 30, 180);
    private static final DoubleSetting FULL_LOCK_SMOOTHING =
            decimal("silentaura.fullLock.smoothing", 0.0D, 0.0D, 1.0D);
    private static final DoubleSetting FULL_LOCK_PREDICTION =
            decimal("silentaura.fullLock.prediction", 1.0D, 0.0D, 3.0D);
    private static final BooleanSetting RETURN_ROTATION = bool("silentaura.returnRotation", true);
    private static final DoubleSetting RETURN_SMOOTH = decimal("silentaura.returnSmooth", 0.45D, 0.05D, 1.0D);
    private static final DoubleSetting JITTER = decimal("silentaura.jitter", 0.38D, 0.0D, 1.0D);
    private static final DoubleSetting JITTER_SPEED = decimal("silentaura.jitterSpeed", 0.85D, 0.1D, 3.0D);
    private static final DoubleSetting SETTLED_JITTER = decimal("silentaura.settledJitter", 0.55D, 0.0D, 1.0D);
    private static final IntSetting AIM_WANDER_TICKS = integer("silentaura.aimWanderTicks", 9, 2, 40);
    private static final DoubleSetting AIM_WANDER = decimal("silentaura.aimWander", 0.45D, 0.0D, 1.0D);
    private static final DoubleSetting PREDICTION_LEAD = decimal("silentaura.predictionLead", 0.5D, 0.0D, 2.0D);
    private static final ModeSetting<AimMode> AIM_MODE = new ModeSetting.Builder<AimMode>()
            .name("silentaura.aimMode").defaultValue(AimMode.LOCK)
            .option(AimMode.BALANCE, "balance", "balanced", "enhanced", "enhance")
            .option(AimMode.LOCK, "lock")
            .option(AimMode.FULL_LOCK, "full_lock", "full-lock", "fulllock").build();
    private static final BooleanSetting MATRIX_COMPATIBILITY =
            bool("silentaura.matrix", false);
    private static final BooleanSetting CRITICAL_INTEGRATION =
            bool("silentaura.critical", true);
    private static final ModeSetting<AimPoint> AIM_POINT = new ModeSetting.Builder<AimPoint>()
            .name("silentaura.aimPoint").defaultValue(AimPoint.CENTER)
            .option(AimPoint.CENTER, "center", "centre")
            .option(AimPoint.CLOSEST, "closest", "close").build();
    private static final DoubleSetting PREDICTION = decimal("silentaura.predictionStrength", 1.0D, 0.0D, 3.0D);
    private static final DoubleSetting MIN_CHARGE = decimal("silentaura.minCharge", 0.7D, 0.7D, 1.3D);
    private static final DoubleSetting MAX_CHARGE = decimal("silentaura.maxCharge", 1.0D, 0.7D, 1.3D);
    private static final BooleanSetting BLOCK = bool("silentaura.block", true);
    private static final BooleanSetting TARGET_PLAYERS = bool("silentaura.target.player", true);
    private static final BooleanSetting TARGET_MOBS = bool("silentaura.target.mob", false);
    private static final StringSetting TARGET_ENTITIES = text("silentaura.target.entities", "");
    private static Set<Identifier> targetEntityTypes = Targeting.parseEntityTypeIds(TARGET_ENTITIES.get());

    private SilentAuraConfig() {}

    public static boolean enabled() { return ENABLED.get(); }
    public static void enabled(boolean value) { ENABLED.set(value); }
    /** Requested combat distance; runtime caps it to the player's safe attack reach. */
    public static double aimRange() { return RANGE.get(); }
    public static double scanExtra() { return SCAN_EXTRA.get(); }
    public static double fov() { return FOV.get(); }
    public static boolean switchTargetMode() { return TARGET_MODE.get() == TargetMode.SWITCH; }
    public static String targetMode() { return TARGET_MODE.serialized(); }
    public static List<String> targetModeOptions() { return TARGET_MODE.optionIds(); }
    public static int hurtTime() { return HURT_TIME.get(); }
    public static double smooth() { return SMOOTH.get(); }
    public static boolean returnRotation() { return RETURN_ROTATION.get(); }
    public static double returnSmooth() { return RETURN_SMOOTH.get(); }
    public static double jitter() { return JITTER.get(); }
    public static double jitterSpeed() { return JITTER_SPEED.get(); }
    public static double settledJitter() { return SETTLED_JITTER.get(); }
    public static int aimWanderTicks() { return AIM_WANDER_TICKS.get(); }
    public static double aimWander() { return AIM_WANDER.get(); }
    public static double predictionLead() { return PREDICTION_LEAD.get(); }
    public static boolean balanceMode() { return AIM_MODE.get() == AimMode.BALANCE; }
    public static boolean lockMode() { return AIM_MODE.get() != AimMode.BALANCE; }
    public static boolean fullLockMode() { return AIM_MODE.get() == AimMode.FULL_LOCK; }
    public static int fullLockAngleStep() { return FULL_LOCK_ANGLE_STEP.get(); }
    public static double fullLockSmoothing() { return FULL_LOCK_SMOOTHING.get(); }
    public static double fullLockPrediction() { return FULL_LOCK_PREDICTION.get(); }
    public static boolean matrixCompatibility() {
        return MATRIX_COMPATIBILITY.get() && !fullLockMode();
    }
    public static boolean criticalIntegration() { return CRITICAL_INTEGRATION.get(); }
    public static String aimMode() { return AIM_MODE.serialized(); }
    public static List<String> aimModeOptions() { return AIM_MODE.optionIds(); }
    public static boolean closestAimPoint() { return AIM_POINT.get() == AimPoint.CLOSEST; }
    public static String aimPoint() { return AIM_POINT.serialized(); }
    public static List<String> aimPointOptions() { return AIM_POINT.optionIds(); }
    public static double prediction() { return PREDICTION.get(); }
    public static double minCharge() { return MIN_CHARGE.get(); }
    public static double maxCharge() { return MAX_CHARGE.get(); }
    public static boolean block() { return BLOCK.get(); }
    public static boolean targetPlayers() { return TARGET_PLAYERS.get(); }
    public static boolean targetMobs() { return TARGET_MOBS.get(); }
    public static Set<Identifier> targetEntityTypes() { return Collections.unmodifiableSet(targetEntityTypes); }

    public static void range(double value) { RANGE.set(value); }
    public static void scanExtra(double value) { SCAN_EXTRA.set(value); }
    public static void fov(double value) { FOV.set(value); }
    public static void hurtTime(int value) { HURT_TIME.set(value); }
    public static void smooth(double value) { SMOOTH.set(value); }
    public static void fullLockAngleStep(int value) { FULL_LOCK_ANGLE_STEP.set(value); }
    public static void fullLockSmoothing(double value) { FULL_LOCK_SMOOTHING.set(value); }
    public static void fullLockPrediction(double value) { FULL_LOCK_PREDICTION.set(value); }
    public static void returnRotation(boolean value) { RETURN_ROTATION.set(value); }
    public static void returnSmooth(double value) { RETURN_SMOOTH.set(value); }
    public static void jitter(double value) { JITTER.set(value); }
    public static void jitterSpeed(double value) { JITTER_SPEED.set(value); }
    public static void settledJitter(double value) { SETTLED_JITTER.set(value); }
    public static void aimWanderTicks(int value) { AIM_WANDER_TICKS.set(value); }
    public static void aimWander(double value) { AIM_WANDER.set(value); }
    public static void predictionLead(double value) { PREDICTION_LEAD.set(value); }
    public static void matrixCompatibility(boolean value) { MATRIX_COMPATIBILITY.set(value); }
    public static void criticalIntegration(boolean value) { CRITICAL_INTEGRATION.set(value); }
    public static void prediction(double value) { PREDICTION.set(value); }
    public static void block(boolean value) { BLOCK.set(value); }
    public static boolean aimMode(String value) {
        AIM_MODE.deserialize(value);
        return true;
    }

    public static boolean aimPoint(String value) {
        AIM_POINT.deserialize(value);
        return true;
    }

    public static boolean targetMode(String value) {
        TARGET_MODE.deserialize(value);
        return true;
    }

    public static void charge(double min, double max) {
        MIN_CHARGE.set(Math.min(min, max));
        MAX_CHARGE.set(Math.max(min, max));
    }

    public static boolean targetCategory(String category, boolean value) {
        if ("player".equalsIgnoreCase(category)) { TARGET_PLAYERS.set(value); return true; }
        if ("mob".equalsIgnoreCase(category)) { TARGET_MOBS.set(value); return true; }
        return false;
    }

    public static boolean targetEntityType(String raw, boolean add) {
        Identifier id = Targeting.parseEntityTypeId(raw);
        if (id == null || BuiltInRegistries.ENTITY_TYPE.getOptional(id).isEmpty()) return false;
        Set<Identifier> updated = Targeting.parseEntityTypeIds(TARGET_ENTITIES.get());
        if (add) updated.add(id); else updated.remove(id);
        targetEntityTypes = updated;
        TARGET_ENTITIES.set(Targeting.serializeEntityTypeIds(updated));
        return true;
    }

    public static String targetStatus() {
        return Targeting.configuredTargetStatus(targetPlayers(), targetMobs(), targetEntityTypes());
    }

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder().name(key).defaultValue(value).build();
    }
    private static DoubleSetting decimal(String key, double value, double min, double max) {
        return new DoubleSetting.Builder().name(key).defaultValue(value).range(min, max).build();
    }
    private static IntSetting integer(String key, int value, int min, int max) {
        return new IntSetting.Builder().name(key).defaultValue(value).range(min, max).build();
    }
    private static StringSetting text(String key, String value) {
        return new StringSetting.Builder().name(key).defaultValue(value).build();
    }

    private enum TargetMode { SWITCH, SINGLE }
    private enum AimMode { BALANCE, LOCK, FULL_LOCK }
    private enum AimPoint { CENTER, CLOSEST }

    // Debug
    private static final BooleanSetting DEBUGGER = bool("silentaura.debugger", false);

    public static boolean debugger() { return DEBUGGER.get(); }
    public static void debugger(boolean value) { DEBUGGER.set(value); }
}
