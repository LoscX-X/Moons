package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.utils.prediction.MotionPrediction;
import com.blanoir.moons.client.utils.registry.RegistryLists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Settings only. Runtime state deliberately lives outside this class. */
public final class SilentAuraConfig {
    private static final BooleanSetting ENABLED = bool("silentaura.enabled", false);
    private static final BooleanSetting BLOCK = bool("silentaura.block", true);
    private static final BooleanSetting BLOCK_VISUAL = bool("silentaura.block.visual", true);
    private static final DoubleSetting BLOCK_RANGE =
            decimal("silentaura.block.range", 4.5D, 1D, 8D);
    private static final ModeSetting<CombatMode> COMBAT_MODE =
            new ModeSetting.Builder<CombatMode>()
                    .name("silentaura.combatMode")
                    .defaultValue(CombatMode.LATEST)
                    .option(CombatMode.LEGACY, "legacy")
                    .option(CombatMode.LATEST, "latest")
                    .build();
    private static final DoubleSetting MIN_CPS = decimal("silentaura.minCps", 10D, 1D, 20D);
    private static final DoubleSetting MAX_CPS = decimal("silentaura.maxCps", 14D, 1D, 20D);
    private static final DoubleSetting RANGE = decimal("silentaura.range", 3.7D, 1.0D, 6.0D);
    private static final BooleanSetting THROUGH_BLOCKS = bool("silentaura.throughBlocks", false);
    private static final DoubleSetting SCAN_EXTRA =
            decimal("silentaura.scanExtra", 2.5D, 0.0D, 7.0D);
    private static final DoubleSetting FOV = decimal("silentaura.fov", 180.0D, 1.0D, 360.0D);
    private static final ModeSetting<TargetMode> TARGET_MODE =
            new ModeSetting.Builder<TargetMode>()
                    .name("silentaura.targetMode")
                    .defaultValue(TargetMode.SWITCH)
                    .option(TargetMode.SWITCH, "switch")
                    .option(TargetMode.SINGLE, "single")
                    .build();
    private static final IntSetting HURT_TIME = integer("silentaura.hurtTime", 10, 0, 10);
    private static final DoubleSetting SMOOTH = decimal("silentaura.smooth", 0.58D, 0.05D, 1.0D);
    private static final IntSetting FULL_LOCK_ANGLE_STEP =
            integer("silentaura.fullLock.angleStep", 90, 30, 180);
    private static final DoubleSetting FULL_LOCK_SMOOTHING =
            decimal("silentaura.fullLock.smoothing", 0.0D, 0.0D, 1.0D);
    private static final DoubleSetting FULL_LOCK_PREDICTION =
            decimal("silentaura.fullLock.prediction", 1.0D, 0.0D, 3.0D);
    private static final BooleanSetting RETURN_ROTATION = bool("silentaura.returnRotation", true);
    private static final DoubleSetting RETURN_SMOOTH =
            decimal("silentaura.returnSmooth", 0.45D, 0.05D, 1.0D);
    private static final DoubleSetting JITTER = decimal("silentaura.jitter", 0.38D, 0.0D, 1.0D);
    private static final DoubleSetting JITTER_SPEED =
            decimal("silentaura.jitterSpeed", 0.85D, 0.1D, 3.0D);
    private static final DoubleSetting SETTLED_JITTER =
            decimal("silentaura.settledJitter", 0.55D, 0.0D, 1.0D);
    private static final IntSetting AIM_WANDER_TICKS =
            integer("silentaura.aimWanderTicks", 9, 2, 40);
    private static final DoubleSetting AIM_WANDER =
            decimal("silentaura.aimWander", 0.45D, 0.0D, 1.0D);
    private static final DoubleSetting PREDICTION_LEAD =
            decimal("silentaura.predictionLead", 0.75D, 0.0D, 2.0D);
    private static final DoubleSetting PREDICTION_MAX_SPEED =
            decimal("silentaura.motion.maxSpeed", 1.5D, .1D, 3D);
    private static final DoubleSetting PREDICTION_MAX_ACCELERATION =
            decimal("silentaura.motion.maxAcceleration", .12D, 0D, 1D);
    private static final DoubleSetting PREDICTION_MAX_HORIZON =
            decimal("silentaura.motion.maxHorizon", 3D, 0D, 6D);
    private static final DoubleSetting PREDICTION_VERTICAL_SCALE =
            decimal("silentaura.motion.verticalScale", .35D, 0D, 1D);
    private static final DoubleSetting PREDICTION_MAX_TURN_RATE =
            decimal("silentaura.motion.maxTurnRate", 0D, 0D, 90D);
    private static final DoubleSetting PREDICTION_MAX_TURN_ANGLE =
            decimal("silentaura.motion.maxTurnAngle", 90D, 0D, 180D);
    private static final DoubleSetting PREDICTION_MIN_RESPONSE =
            decimal("silentaura.motion.minResponse", .35D, 0.0D, 3.0D);
    private static final DoubleSetting PREDICTION_MAX_RESPONSE =
            decimal("silentaura.motion.maxResponse", 1.5D, 0.0D, 3.0D);
    private static final ModeSetting<AimMode> AIM_MODE =
            new ModeSetting.Builder<AimMode>()
                    .name("silentaura.aimMode")
                    .defaultValue(AimMode.LOCK)
                    .option(AimMode.BALANCE, "balance")
                    .option(AimMode.LOCK, "lock")
                    .option(AimMode.FULL_LOCK, "full_lock")
                    .build();
    private static final BooleanSetting MATRIX_COMPATIBILITY = bool("silentaura.matrix", false);
    private static final BooleanSetting CRITICAL_INTEGRATION = bool("silentaura.critical", true);
    private static final ModeSetting<AimPoint> AIM_POINT =
            new ModeSetting.Builder<AimPoint>()
                    .name("silentaura.aimPoint")
                    .defaultValue(AimPoint.CENTER)
                    .option(AimPoint.CENTER, "center")
                    .option(AimPoint.CLOSEST, "closest")
                    .build();
    private static final DoubleSetting PREDICTION =
            decimal("silentaura.predictionStrength", 1.0D, 0.0D, 3.0D);
    private static final DoubleSetting MIN_CHARGE =
            decimal("silentaura.minCharge", 0.7D, 0.7D, 1.3D);
    private static final DoubleSetting MAX_CHARGE =
            decimal("silentaura.maxCharge", 1.0D, 0.7D, 1.3D);
    private static final BooleanSetting TARGET_PLAYERS = bool("silentaura.target.player", true);
    private static final StringSetting TARGET_ENTITIES = text();
    private static final Set<Identifier> targetEntityTypes =
            Targeting.parseEntityTypeIds(TARGET_ENTITIES.get());

    private SilentAuraConfig() {}

    /** Feature-owned descriptors; keys, defaults, bounds and modes come from the settings above. */
    public static ModuleRegistry.Setting[] settings() {
        return Descriptors.ALL.clone();
    }

    private static final class Descriptors {
        private static final ModuleRegistry.Setting[] ALL = {
            COMBAT_MODE.describe("combat_mode", "Combat mode", SilentAura::setCombatMode),
            BLOCK.describe(
                    "block",
                    "Auto block",
                    (client, value) -> {
                        SilentAuraBlock.reset(client);
                        BLOCK.set(value);
                        return 1;
                    }),
            BLOCK_VISUAL
                    .describe(
                            "block_visual",
                            "Visual blocking",
                            (client, value) -> {
                                SilentAuraBlock.reset(client);
                                BLOCK_VISUAL.set(value);
                                return 1;
                            })
                    .visibleWhen(() -> block() && legacyCombat()),
            BLOCK_RANGE
                    .describe(
                            "block_range",
                            "Block range",
                            .1,
                            (client, value) -> {
                                SilentAuraBlock.reset(client);
                                BLOCK_RANGE.set(value);
                                return 1;
                            })
                    .visibleWhen(SilentAuraConfig::block),
            MIN_CPS.describeRange("cps", "Clicks per second", MAX_CPS, .1, SilentAura::setCps)
                    .visibleWhen(SilentAuraConfig::legacyCombat),
            RANGE.describe("range", "Attack range", .05, SilentAura::setRange),
            THROUGH_BLOCKS.describe(
                    "through_blocks", "Through blocks", SilentAura::setThroughBlocks),
            SCAN_EXTRA.describe("scan_extra", "Scan range increase", .1, SilentAura::setScanExtra),
            FOV.describe("fov", "FOV", 1, SilentAura::setFov),
            TARGET_MODE.describe("target_mode", "Target mode", SilentAura::setTargetMode),
            HURT_TIME.describe("hurt_time", "Maximum hurt time", 1, SilentAura::setHurtTime),
            AIM_MODE.describe("aim_mode", "Aim mode", SilentAura::setAimMode),
            SMOOTH.describe("smooth", "Smooth", .01, SilentAura::setSmooth)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            RETURN_ROTATION.describe(
                    "return_rotation", "Return rotation", SilentAura::setReturnRotation),
            RETURN_SMOOTH
                    .describe("return_smooth", "Return smooth", .01, SilentAura::setReturnSmooth)
                    .visibleWhen(SilentAuraConfig::returnRotation),
            JITTER.describe("jitter", "Path jitter", .01, SilentAura::setJitter)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            JITTER_SPEED
                    .describe("jitter_speed", "Jitter speed", .05, SilentAura::setJitterSpeed)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            SETTLED_JITTER
                    .describe("settled_jitter", "Settled sway", .01, SilentAura::setSettledJitter)
                    .visibleWhen(SilentAuraConfig::balanceMode),
            AIM_WANDER
                    .describe("aim_wander", "Aim wander", .01, SilentAura::setAimWander)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            AIM_WANDER_TICKS
                    .describe(
                            "aim_wander_ticks", "Wander interval", 1, SilentAura::setAimWanderTicks)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            PREDICTION_LEAD
                    .describe(
                            "prediction_lead", "Velocity lead", .05, SilentAura::setPredictionLead)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            FULL_LOCK_ANGLE_STEP
                    .describe(
                            "full_lock_angle_step",
                            "Full-lock angle step",
                            1,
                            SilentAura::setFullLockAngleStep)
                    .visibleWhen(SilentAuraConfig::fullLockMode),
            FULL_LOCK_SMOOTHING
                    .describe(
                            "full_lock_smoothing",
                            "Full-lock smoothing",
                            .01,
                            SilentAura::setFullLockSmoothing)
                    .visibleWhen(SilentAuraConfig::fullLockMode),
            FULL_LOCK_PREDICTION
                    .describe(
                            "full_lock_prediction",
                            "Full-lock lead ticks",
                            .05,
                            SilentAura::setFullLockPrediction)
                    .visibleWhen(SilentAuraConfig::fullLockMode),
            PREDICTION_MAX_SPEED.describe(
                    "prediction_max_speed",
                    "Max target speed (blocks/tick)",
                    .05,
                    SilentAura::setPredictionMaxSpeed),
            PREDICTION_MAX_ACCELERATION.describe(
                    "prediction_max_acceleration",
                    "Max target acceleration",
                    .01,
                    SilentAura::setPredictionMaxAcceleration),
            PREDICTION_MAX_HORIZON.describe(
                    "prediction_max_horizon",
                    "Max lead ticks",
                    .05,
                    SilentAura::setPredictionMaxHorizon),
            PREDICTION_VERTICAL_SCALE
                    .describe(
                            "prediction_vertical_scale",
                            "Vertical lead weight",
                            .01,
                            SilentAura::setPredictionVerticalScale)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            PREDICTION_MAX_TURN_RATE.describe(
                    "prediction_max_turn_rate",
                    "Max movement turn (deg/tick)",
                    1,
                    SilentAura::setPredictionMaxTurnRate),
            PREDICTION_MAX_TURN_ANGLE
                    .describe(
                            "prediction_max_turn_angle",
                            "Max predicted turn (deg)",
                            1,
                            SilentAura::setPredictionMaxTurnAngle)
                    .visibleWhen(SilentAuraConfig::predictionTurningEnabled),
            PREDICTION_MIN_RESPONSE.describeRange(
                    "prediction_response",
                    "Prediction response ticks",
                    PREDICTION_MAX_RESPONSE,
                    .05,
                    SilentAura::setPredictionResponse),
            MATRIX_COMPATIBILITY
                    .describe("matrix", "Limitation", SilentAura::setMatrixCompatibility)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            CRITICAL_INTEGRATION
                    .describe("critical", "Critical", SilentAura::setCriticalIntegration)
                    .visibleWhen(() -> !SilentAuraConfig.legacyCombat()),
            AIM_POINT
                    .describe("aim_point", "Aim point", SilentAura::setAimPoint)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            PREDICTION
                    .describe(
                            "prediction", "Turn prediction", .05, SilentAura::setPredictionStrength)
                    .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
            MIN_CHARGE
                    .describeRange(
                            "charge", "Attack charge", MAX_CHARGE, .01, SilentAura::setCharge)
                    .visibleWhen(() -> !SilentAuraConfig.legacyCombat()),
            TARGET_PLAYERS.describe(
                    "target_players",
                    "Target players",
                    (client, value) -> SilentAura.setTargetCategory(client, "player", value)),
            RegistryLists.setting(
                    "target_entities",
                    "Other entities",
                    "mob",
                    SilentAuraConfig::selectedEntities,
                    (client, value) -> {
                        setEntities(value);
                        SilentAuraRuntime.resetTargeting();
                    },
                    new JsonArray()),
            DEBUGGER.describe("debugger", "Debugger", SilentAura::setDebugger)
        };
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static boolean block() {
        return BLOCK.get();
    }

    public static boolean blockVisual() {
        return BLOCK_VISUAL.get();
    }

    public static double blockRange() {
        return BLOCK_RANGE.get();
    }

    public static boolean legacyCombat() {
        return COMBAT_MODE.get() == CombatMode.LEGACY;
    }

    public static String combatMode() {
        return COMBAT_MODE.serialized();
    }

    public static boolean combatMode(String value) {
        return COMBAT_MODE.tryDeserialize(value);
    }

    public static double minCps() {
        return MIN_CPS.get();
    }

    public static double maxCps() {
        return MAX_CPS.get();
    }

    public static void cps(double min, double max) {
        MIN_CPS.set(Math.min(min, max));
        MAX_CPS.set(Math.max(min, max));
    }

    public static void enabled(boolean value) {
        ENABLED.set(value);
    }

    /** Requested combat distance; runtime caps it to the player's safe attack reach. */
    public static double aimRange() {
        return RANGE.get();
    }

    public static boolean throughBlocks() {
        return THROUGH_BLOCKS.get();
    }

    public static void throughBlocks(boolean value) {
        THROUGH_BLOCKS.set(value);
    }

    public static double scanExtra() {
        return SCAN_EXTRA.get();
    }

    public static double fov() {
        return FOV.get();
    }

    public static boolean switchTargetMode() {
        return TARGET_MODE.get() == TargetMode.SWITCH;
    }

    public static String targetMode() {
        return TARGET_MODE.serialized();
    }

    public static List<String> targetModeOptions() {
        return TARGET_MODE.optionIds();
    }

    public static int hurtTime() {
        return HURT_TIME.get();
    }

    public static double smooth() {
        return SMOOTH.get();
    }

    public static boolean returnRotation() {
        return RETURN_ROTATION.get();
    }

    public static double returnSmooth() {
        return RETURN_SMOOTH.get();
    }

    public static double jitter() {
        return JITTER.get();
    }

    public static double jitterSpeed() {
        return JITTER_SPEED.get();
    }

    public static double settledJitter() {
        return SETTLED_JITTER.get();
    }

    public static int aimWanderTicks() {
        return AIM_WANDER_TICKS.get();
    }

    public static double aimWander() {
        return AIM_WANDER.get();
    }

    public static double predictionLead() {
        return PREDICTION_LEAD.get();
    }

    public static void predictionMaxSpeed(double value) {
        PREDICTION_MAX_SPEED.set(value);
    }

    public static void predictionMaxAcceleration(double value) {
        PREDICTION_MAX_ACCELERATION.set(value);
    }

    public static void predictionMaxHorizon(double value) {
        PREDICTION_MAX_HORIZON.set(value);
    }

    public static void predictionVerticalScale(double value) {
        PREDICTION_VERTICAL_SCALE.set(value);
    }

    public static void predictionMaxTurnRate(double value) {
        PREDICTION_MAX_TURN_RATE.set(value);
    }

    public static void predictionMaxTurnAngle(double value) {
        PREDICTION_MAX_TURN_ANGLE.set(value);
    }

    public static void predictionResponse(double min, double max) {
        PREDICTION_MIN_RESPONSE.set(Math.min(min, max));
        PREDICTION_MAX_RESPONSE.set(Math.max(min, max));
    }

    public static boolean predictionTurningEnabled() {
        return PREDICTION_MAX_TURN_RATE.get() > 0.0D;
    }

    public static MotionPrediction.Parameters motionPredictionParameters() {
        double minResponse = PREDICTION_MIN_RESPONSE.get();
        double maxResponse = PREDICTION_MAX_RESPONSE.get();
        return MotionPrediction.Parameters.builder()
                .maxSpeed(PREDICTION_MAX_SPEED.get())
                .maxAcceleration(PREDICTION_MAX_ACCELERATION.get())
                .maxHorizonTicks(PREDICTION_MAX_HORIZON.get())
                .verticalScale(PREDICTION_VERTICAL_SCALE.get())
                .responseTicks(
                        Math.min(minResponse, maxResponse), Math.max(minResponse, maxResponse))
                .maxTurnRateDegreesPerTick(PREDICTION_MAX_TURN_RATE.get())
                .maxTurnAngleDegrees(PREDICTION_MAX_TURN_ANGLE.get())
                .build();
    }

    public static boolean balanceMode() {
        return AIM_MODE.get() == AimMode.BALANCE;
    }

    public static boolean lockMode() {
        return AIM_MODE.get() != AimMode.BALANCE;
    }

    public static boolean fullLockMode() {
        return AIM_MODE.get() == AimMode.FULL_LOCK;
    }

    public static int fullLockAngleStep() {
        return FULL_LOCK_ANGLE_STEP.get();
    }

    public static double fullLockSmoothing() {
        return FULL_LOCK_SMOOTHING.get();
    }

    public static double fullLockPrediction() {
        return FULL_LOCK_PREDICTION.get();
    }

    public static boolean matrixCompatibility() {
        return MATRIX_COMPATIBILITY.get() && !fullLockMode();
    }

    public static boolean criticalIntegration() {
        return !legacyCombat() && CRITICAL_INTEGRATION.get();
    }

    public static String aimMode() {
        return AIM_MODE.serialized();
    }

    public static List<String> aimModeOptions() {
        return AIM_MODE.optionIds();
    }

    public static boolean closestAimPoint() {
        return AIM_POINT.get() == AimPoint.CLOSEST;
    }

    public static String aimPoint() {
        return AIM_POINT.serialized();
    }

    public static List<String> aimPointOptions() {
        return AIM_POINT.optionIds();
    }

    public static double prediction() {
        return PREDICTION.get();
    }

    public static double minCharge() {
        return MIN_CHARGE.get();
    }

    public static double maxCharge() {
        return MAX_CHARGE.get();
    }

    public static boolean targetPlayers() {
        return TARGET_PLAYERS.get();
    }

    public static JsonArray selectedEntities() {
        return RegistryLists.entityIds(targetEntityTypes);
    }

    private static void setEntities(JsonElement value) {
        var next = RegistryLists.readEntityIds(value);
        targetEntityTypes.clear();
        targetEntityTypes.addAll(next);
        TARGET_ENTITIES.set(Targeting.serializeEntityTypeIds(targetEntityTypes));
    }

    public static Set<Identifier> targetEntityTypes() {
        return Collections.unmodifiableSet(targetEntityTypes);
    }

    public static void range(double value) {
        RANGE.set(value);
    }

    public static void scanExtra(double value) {
        SCAN_EXTRA.set(value);
    }

    public static void fov(double value) {
        FOV.set(value);
    }

    public static void hurtTime(int value) {
        HURT_TIME.set(value);
    }

    public static void smooth(double value) {
        SMOOTH.set(value);
    }

    public static void fullLockAngleStep(int value) {
        FULL_LOCK_ANGLE_STEP.set(value);
    }

    public static void fullLockSmoothing(double value) {
        FULL_LOCK_SMOOTHING.set(value);
    }

    public static void fullLockPrediction(double value) {
        FULL_LOCK_PREDICTION.set(value);
    }

    public static void returnRotation(boolean value) {
        RETURN_ROTATION.set(value);
    }

    public static void returnSmooth(double value) {
        RETURN_SMOOTH.set(value);
    }

    public static void jitter(double value) {
        JITTER.set(value);
    }

    public static void jitterSpeed(double value) {
        JITTER_SPEED.set(value);
    }

    public static void settledJitter(double value) {
        SETTLED_JITTER.set(value);
    }

    public static void aimWanderTicks(int value) {
        AIM_WANDER_TICKS.set(value);
    }

    public static void aimWander(double value) {
        AIM_WANDER.set(value);
    }

    public static void predictionLead(double value) {
        PREDICTION_LEAD.set(value);
    }

    public static void matrixCompatibility(boolean value) {
        MATRIX_COMPATIBILITY.set(value);
    }

    public static void criticalIntegration(boolean value) {
        CRITICAL_INTEGRATION.set(value);
    }

    public static void prediction(double value) {
        PREDICTION.set(value);
    }

    public static boolean aimMode(String value) {
        return AIM_MODE.tryDeserialize(value);
    }

    public static boolean aimPoint(String value) {
        return AIM_POINT.tryDeserialize(value);
    }

    public static boolean targetMode(String value) {
        return TARGET_MODE.tryDeserialize(value);
    }

    public static void charge(double min, double max) {
        MIN_CHARGE.set(Math.min(min, max));
        MAX_CHARGE.set(Math.max(min, max));
    }

    public static boolean targetCategory(String category, boolean value) {
        if ("player".equalsIgnoreCase(category)) {
            TARGET_PLAYERS.set(value);
            return true;
        }
        if ("mob".equalsIgnoreCase(category)) {
            setEntities(RegistryLists.entityIds(value ? RegistryLists.allMobIds() : Set.of()));
            return true;
        }
        return false;
    }

    public static String targetStatus() {
        return Targeting.configuredTargetStatus(targetPlayers(), false, targetEntityTypes());
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

    private static StringSetting text() {
        return new StringSetting.Builder()
                .name("silentaura.target.entities")
                .defaultValue("")
                .build();
    }

    private enum TargetMode {
        SWITCH,
        SINGLE
    }

    private enum CombatMode {
        LEGACY,
        LATEST
    }

    private enum AimMode {
        BALANCE,
        LOCK,
        FULL_LOCK
    }

    private enum AimPoint {
        CENTER,
        CLOSEST
    }

    // Debug
    private static final BooleanSetting DEBUGGER = bool("silentaura.debugger", false);

    public static boolean debugger() {
        return DEBUGGER.get();
    }

    public static void debugger(boolean value) {
        DEBUGGER.set(value);
    }
}
