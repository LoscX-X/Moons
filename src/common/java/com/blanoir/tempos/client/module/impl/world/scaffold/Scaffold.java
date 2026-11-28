package com.blanoir.tempos.client.module.impl.world.scaffold;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.choice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;
import static com.blanoir.tempos.client.module.framework.SettingFactory.number;
import static com.blanoir.tempos.client.module.framework.SettingFactory.numeric;
import static com.blanoir.tempos.client.module.framework.SettingFactory.rangeInts;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;

import java.util.List;

@ModuleInfo(
        category = ModuleCategory.WORLD,
        description = "Helps place blocks while bridging or building upward.")
public final class Scaffold extends ClientFeature {
    public static final Scaffold INSTANCE = new Scaffold();

    private Scaffold() {
        super("scaffold", "Scaffold");
    }

    public static Input filterMovementInput(Minecraft client, Input input) {
        return ScaffoldManager.filterMovementInput(client, input);
    }

    public static boolean returningTellySelected() {
        return ScaffoldManager.returningTellySelected();
    }

    public static int setTellyReturnSpeed(Minecraft client, double value) {
        return ScaffoldManager.setTellyReturnSpeed(client, value);
    }

    public static int setLegitSneakCheck(Minecraft client, boolean value) {
        return ScaffoldManager.setLegitSneakCheck(client, value);
    }

    public static int setLegitEdgeOffset(Minecraft client, double value) {
        return ScaffoldManager.setLegitEdgeOffset(client, value);
    }

    @Override
    public void registerEvents() {
        ScaffoldManager.init();
    }

    @Override
    public boolean isEnabled() {
        return ScaffoldManager.enabled();
    }

    @Override
    public int setEnabled(Minecraft c, boolean v) {
        return ScaffoldManager.setEnabled(c, v);
    }

    public static int showStatus(Minecraft c) {
        return ScaffoldManager.showStatus(c);
    }

    @Override
    public String statusTag() {
        return ScaffoldManager.statusTag();
    }

    public static String modeName() {
        return ScaffoldManager.modeName();
    }

    public static int setMode(Minecraft c, String v) {
        return ScaffoldManager.setMode(c, v);
    }

    public static List<String> modeOptions() {
        return ScaffoldManager.modeOptions();
    }

    public static boolean legitSelected() {
        return ScaffoldManager.legitSelected();
    }

    public static boolean tellySelected() {
        return ScaffoldManager.tellySelected();
    }

    public static boolean smoothTellySelected() {
        return ScaffoldManager.smoothTellySelected();
    }

    public static boolean plannedTellySelected() {
        return ScaffoldManager.plannedTellySelected();
    }

    public static int setPacketPlanner(Minecraft client, boolean value) {
        return ScaffoldManager.setPacketPlanner(client, value);
    }

    public static int setRotationPlanTicks(Minecraft client, int value) {
        return ScaffoldManager.setRotationPlanTicks(client, value);
    }

    public static int setYawAcceleration(Minecraft client, double value) {
        return ScaffoldManager.setYawAcceleration(client, value);
    }

    public static int setPitchAcceleration(Minecraft client, double value) {
        return ScaffoldManager.setPitchAcceleration(client, value);
    }

    public static int setAccelerationVariation(Minecraft client, double value) {
        return ScaffoldManager.setAccelerationVariation(client, value);
    }

    public static int setAccelerationResponse(Minecraft client, double value) {
        return ScaffoldManager.setAccelerationResponse(client, value);
    }

    public static int setPointJitterStrength(Minecraft client, double value) {
        return ScaffoldManager.setPointJitterStrength(client, value);
    }

    public static int setPointJitterSpeed(Minecraft client, double value) {
        return ScaffoldManager.setPointJitterSpeed(client, value);
    }

    public static boolean standardBridgeSelected() {
        return ScaffoldManager.standardBridgeSelected();
    }

    public static boolean godBridgeSelected() {
        return ScaffoldManager.godBridgeSelected();
    }

    public static boolean godBridgeSneakSelected() {
        return ScaffoldManager.godBridgeSneakSelected();
    }

    public static int setGodBridgeSneak(Minecraft client, boolean value) {
        return ScaffoldManager.setGodBridgeSneak(client, value);
    }

    public static int setGodBridgeSneakTime(Minecraft client, int min, int max) {
        return ScaffoldManager.setGodBridgeSneakTime(client, min, max);
    }

    public static int setGodBridgeEdgeOffset(Minecraft client, double value) {
        return ScaffoldManager.setGodBridgeEdgeOffset(client, value);
    }

    public static List<String> tellyRotationOptions() {
        return ScaffoldManager.tellyRotationOptions();
    }

    public static int setTellyRotation(Minecraft c, String v) {
        return ScaffoldManager.setTellyRotation(c, v);
    }

    public static boolean tellyBodyLockEnabled() {
        return ScaffoldManager.tellyBodyLockEnabled();
    }

    public static int setTellyBodyLock(Minecraft c, boolean v) {
        return ScaffoldManager.setTellyBodyLock(c, v);
    }

    public static boolean tellyBpsLimitSelected() {
        return ScaffoldManager.tellyBpsLimitSelected();
    }

    public static int setLegitDelay(Minecraft c, int min, int max) {
        return ScaffoldManager.setLegitDelay(c, min, max);
    }

    public static int setTellyStartSpeed(Minecraft c, double v) {
        return ScaffoldManager.setTellyStartSpeed(c, v);
    }

    public static int setTellyTrackSpeed(Minecraft c, double v) {
        return ScaffoldManager.setTellyTrackSpeed(c, v);
    }

    public static int setTellyDelayMode(Minecraft c, String v) {
        return ScaffoldManager.setTellyDelayMode(c, v);
    }

    public static List<String> tellyDelayModeOptions() {
        return ScaffoldManager.tellyDelayModeOptions();
    }

    public static int setFaceSampling(Minecraft c, String v) {
        return ScaffoldManager.setFaceSampling(c, v);
    }

    public static List<String> faceSamplingOptions() {
        return ScaffoldManager.faceSamplingOptions();
    }

    public static int setTellyPlaceAngle(Minecraft c, double v) {
        return ScaffoldManager.setTellyPlaceAngle(c, v);
    }

    public static int setTellyMaxForwardBlocks(Minecraft c, int v) {
        return ScaffoldManager.setTellyMaxForwardBlocks(c, v);
    }

    public static int setTellyPlaceDelay(Minecraft c, int v) {
        return ScaffoldManager.setTellyPlaceDelay(c, v);
    }

    public static int setTellyEarlyRotation(Minecraft c, boolean v) {
        return ScaffoldManager.setTellyEarlyRotation(c, v);
    }

    public static boolean earlyTellyRotationSelected() {
        return ScaffoldManager.earlyTellyRotationSelected();
    }

    public static int tellyRotationDelay() {
        return ScaffoldManager.tellyRotationDelay();
    }

    public static int setTellyRotationDelay(Minecraft c, int v) {
        return ScaffoldManager.setTellyRotationDelay(c, v);
    }

    public static int setTellyBlocksPerSecondEnabled(Minecraft c, boolean v) {
        return ScaffoldManager.setTellyBlocksPerSecondEnabled(c, v);
    }

    public static int setTellyBlocksPerSecond(Minecraft c, int min, int max) {
        return ScaffoldManager.setTellyBlocksPerSecond(c, min, max);
    }

    public static int setTellyFlat(Minecraft c, boolean v) {
        return ScaffoldManager.setTellyFlat(c, v);
    }

    public static int setTellyFallRescue(Minecraft c, boolean v) {
        return ScaffoldManager.setTellyFallRescue(c, v);
    }

    public static int setMoveFix(Minecraft c, String v) {
        return ScaffoldManager.setMoveFix(c, v);
    }

    public static List<String> moveFixOptions() {
        return ScaffoldManager.moveFixOptions();
    }

    public static int setSprintMode(Minecraft c, String v) {
        return ScaffoldManager.setSprintMode(c, v);
    }

    public static List<String> sprintModeOptions() {
        return ScaffoldManager.sprintModeOptions();
    }

    public static int setTower(Minecraft c, String v) {
        return ScaffoldManager.setTower(c, v);
    }

    public static List<String> towerOptions() {
        return ScaffoldManager.towerOptions();
    }

    public static int setBlockCounter(Minecraft c, boolean v) {
        return ScaffoldManager.setBlockCounter(c, v);
    }

    public static boolean shouldApplyRotation() {
        return ScaffoldManager.shouldApplyRotation();
    }

    public static boolean shouldCorrectMovement() {
        return ScaffoldManager.shouldCorrectMovement();
    }

    public static boolean shouldSuppressSprint(Minecraft c) {
        return ScaffoldManager.shouldSuppressSprint(c);
    }

    public static float getYaw() {
        return ScaffoldManager.yaw();
    }

    public static float getPitch() {
        return ScaffoldManager.pitch();
    }

    public static float getRenderYaw() {
        return ScaffoldManager.renderYaw();
    }

    public static float getRenderPitch() {
        return ScaffoldManager.renderPitch();
    }

    public static float getMovementYaw() {
        return ScaffoldManager.movementYaw();
    }

    public static com.blanoir.tempos.client.utils.rotation.Rotation getPacketRotation() {
        return ScaffoldManager.packetRotation();
    }

    public static boolean cancelManualActions() {
        return ScaffoldManager.cancelManualActions();
    }

    public static boolean cancelUseAction() {
        return ScaffoldManager.cancelUseAction();
    }

    public static boolean handleHotbarSwap(int slot, int offset) {
        return ScaffoldManager.handleHotbarSwap(slot, offset);
    }

    public static ItemStack spoofedItem(ItemStack original) {
        return ScaffoldManager.spoofedItem(original);
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append(
                "scaffold",
                new Setting[] {
                    choice(
                            "mode",
                            "Mode",
                            "scaffold.mode",
                            "legit",
                            Scaffold.modeOptions(),
                            Scaffold::setMode),
                    bool(
                                    "godbridge_sneak",
                                    "Edge sneak",
                                    "scaffold.godBridgeSneak",
                                    true,
                                    Scaffold::setGodBridgeSneak)
                            .visibleWhen(Scaffold::godBridgeSelected),
                    rangeInts(
                                    "godbridge_sneak_ms",
                                    "Sneak hold (ms)",
                                    "scaffold.godBridgeSneakMinMs",
                                    "scaffold.godBridgeSneakMaxMs",
                                    50,
                                    100,
                                    0,
                                    1000,
                                    5,
                                    Scaffold::setGodBridgeSneakTime)
                            .visibleWhen(Scaffold::godBridgeSneakSelected),
                    number(
                                    "godbridge_edge_offset",
                                    "Allowed edge overhang",
                                    "scaffold.godBridgeEdgeOffset",
                                    0.0,
                                    0.0,
                                    .2,
                                    .01,
                                    Scaffold::setGodBridgeEdgeOffset)
                            .visibleWhen(Scaffold::godBridgeSneakSelected),
                    rangeInts(
                                    "legit_delay_ms",
                                    "Sneak delay (ms)",
                                    "scaffold.legitDelayMinMs",
                                    "scaffold.legitDelayMaxMs",
                                    100,
                                    150,
                                    0,
                                    500,
                                    5,
                                    Scaffold::setLegitDelay)
                            .visibleWhen(Scaffold::legitSelected),
                    bool(
                                    "legit_sneak_check",
                                    "Sneak check",
                                    "scaffold.legitSneakCheck",
                                    false,
                                    Scaffold::setLegitSneakCheck)
                            .visibleWhen(Scaffold::legitSelected),
                    number(
                                    "legit_edge_offset",
                                    "Edge offset (blocks)",
                                    "scaffold.legitEdgeOffset",
                                    0,
                                    0,
                                    .3,
                                    .01,
                                    Scaffold::setLegitEdgeOffset)
                            .visibleWhen(Scaffold::legitSelected),
                    choice(
                                    "face_sampling",
                                    "Face sampling",
                                    "scaffold.faceSampling",
                                    "standard",
                                    Scaffold.faceSamplingOptions(),
                                    Scaffold::setFaceSampling)
                            .visibleWhen(Scaffold::standardBridgeSelected),
                    choice(
                                    "telly_rotation",
                                    "Telly rotation",
                                    "scaffold.tellyRotation",
                                    "smooth",
                                    Scaffold.tellyRotationOptions(),
                                    Scaffold::setTellyRotation)
                            .visibleWhen(Scaffold::standardBridgeSelected),
                    bool(
                                    "telly_body_lock",
                                    "Head/body lock",
                                    "scaffold.tellyBodyLock",
                                    true,
                                    Scaffold::setTellyBodyLock)
                            .visibleWhen(Scaffold::tellySelected),
                    number(
                                    "telly_start_speed",
                                    "Start turn limit",
                                    "scaffold.tellyStartSpeed",
                                    85,
                                    1,
                                    180,
                                    .5,
                                    Scaffold::setTellyStartSpeed)
                            .visibleWhen(Scaffold::smoothTellySelected),
                    number(
                                    "telly_track_speed",
                                    "Track turn limit",
                                    "scaffold.tellyTrackSpeed",
                                    45,
                                    1,
                                    180,
                                    .5,
                                    Scaffold::setTellyTrackSpeed)
                            .visibleWhen(Scaffold::smoothTellySelected),
                    bool(
                                    "packet_planner",
                                    "Packet planner",
                                    "scaffold.packetPlanner",
                                    true,
                                    Scaffold::setPacketPlanner)
                            .visibleWhen(Scaffold::smoothTellySelected),
                    integer(
                                    "rotation_plan_ticks",
                                    "Plan horizon (ticks)",
                                    "scaffold.rotationPlanTicks",
                                    1,
                                    1,
                                    5,
                                    1,
                                    Scaffold::setRotationPlanTicks)
                            .visibleWhen(Scaffold::plannedTellySelected),
                    number(
                                    "yaw_acceleration",
                                    "Yaw accel (deg/tick^2)",
                                    "scaffold.yawAcceleration",
                                    40,
                                    2,
                                    180,
                                    1,
                                    Scaffold::setYawAcceleration)
                            .visibleWhen(Scaffold::plannedTellySelected),
                    number(
                                    "pitch_acceleration",
                                    "Pitch accel (deg/tick^2)",
                                    "scaffold.pitchAcceleration",
                                    20,
                                    2,
                                    180,
                                    .5,
                                    Scaffold::setPitchAcceleration)
                            .visibleWhen(Scaffold::plannedTellySelected),
                    number(
                                    "acceleration_variation",
                                    "Accel variation (%)",
                                    "scaffold.accelerationVariation",
                                    15,
                                    0,
                                    50,
                                    1,
                                    Scaffold::setAccelerationVariation)
                            .visibleWhen(Scaffold::plannedTellySelected),
                    number(
                                    "acceleration_response",
                                    "Accel response",
                                    "scaffold.accelerationResponse",
                                    .3,
                                    .05,
                                    1,
                                    .05,
                                    Scaffold::setAccelerationResponse)
                            .visibleWhen(Scaffold::plannedTellySelected),
                    number(
                                    "point_jitter_strength",
                                    "Point jitter strength",
                                    "scaffold.pointJitterStrength",
                                    .38,
                                    0,
                                    1,
                                    .01,
                                    Scaffold::setPointJitterStrength)
                            .visibleWhen(Scaffold::tellySelected),
                    number(
                                    "point_jitter_speed",
                                    "Point jitter speed",
                                    "scaffold.pointJitterSpeed",
                                    .85,
                                    .1,
                                    3,
                                    .05,
                                    Scaffold::setPointJitterSpeed)
                            .visibleWhen(Scaffold::tellySelected),
                    number(
                                    "telly_place_angle",
                                    "Telly place angle",
                                    "scaffold.tellyPlaceAngle",
                                    6,
                                    .5,
                                    20,
                                    .5,
                                    Scaffold::setTellyPlaceAngle)
                            .visibleWhen(Scaffold::standardBridgeSelected),
                    number(
                                    "telly_return_speed",
                                    "Return turn limit",
                                    "scaffold.tellyReturnSpeed",
                                    45,
                                    1,
                                    180,
                                    .5,
                                    Scaffold::setTellyReturnSpeed)
                            .visibleWhen(Scaffold::returningTellySelected),
                    choice(
                                    "telly_delay_mode",
                                    "Air delay mode",
                                    "scaffold.tellyDelayMode",
                                    "fixed",
                                    Scaffold.tellyDelayModeOptions(),
                                    Scaffold::setTellyDelayMode)
                            .visibleWhen(Scaffold::returningTellySelected),
                    integer(
                                    "telly_place_delay",
                                    "Air delay (ticks)",
                                    "scaffold.tellyPlaceDelay",
                                    4,
                                    0,
                                    8,
                                    1,
                                    Scaffold::setTellyPlaceDelay)
                            .visibleWhen(Scaffold::returningTellySelected),
                    bool(
                                    "telly_early_rotation",
                                    "Early rotation",
                                    "scaffold.tellyEarlyRotation",
                                    true,
                                    Scaffold::setTellyEarlyRotation)
                            .visibleWhen(Scaffold::returningTellySelected),
                    numeric(
                                    "telly_rotation_delay",
                                    "Pre-turn wait (ticks)",
                                    "integer",
                                    Scaffold::tellyRotationDelay,
                                    0,
                                    8,
                                    1,
                                    (client, value) ->
                                            Scaffold.setTellyRotationDelay(
                                                    client, (int) Math.round(value)))
                            .withDefault(0)
                            .visibleWhen(Scaffold::earlyTellyRotationSelected),
                    bool(
                                    "telly_bps_limit",
                                    "Limit forward BPS",
                                    "scaffold.tellyBlocksPerSecondEnabled",
                                    true,
                                    Scaffold::setTellyBlocksPerSecondEnabled)
                            .visibleWhen(Scaffold::tellySelected),
                    rangeInts(
                                    "telly_blocks_per_second",
                                    "Blocks per second",
                                    "scaffold.tellyBlocksPerSecondMin",
                                    "scaffold.tellyBlocksPerSecondMax",
                                    3,
                                    4,
                                    1,
                                    20,
                                    1,
                                    Scaffold::setTellyBlocksPerSecond)
                            .visibleWhen(
                                    () ->
                                            Scaffold.tellySelected()
                                                    && Scaffold.tellyBpsLimitSelected()),
                    bool("telly_flat", "Flat", "scaffold.tellyFlat", false, Scaffold::setTellyFlat)
                            .visibleWhen(Scaffold::tellySelected),
                    bool(
                                    "telly_fall_rescue",
                                    "Fall rescue",
                                    "scaffold.tellyFallRescue",
                                    false,
                                    Scaffold::setTellyFallRescue)
                            .visibleWhen(Scaffold::tellySelected),
                    choice(
                                    "strafe",
                                    "Strafe",
                                    "scaffold.moveFix",
                                    "silent",
                                    Scaffold.moveFixOptions(),
                                    Scaffold::setMoveFix)
                            .visibleWhen(Scaffold::tellySelected),
                    choice(
                                    "sprint",
                                    "Sprint",
                                    "scaffold.sprintMode",
                                    "vanilla",
                                    Scaffold.sprintModeOptions(),
                                    Scaffold::setSprintMode)
                            .visibleWhen(Scaffold::tellySelected),
                    choice(
                                    "tower",
                                    "Tower",
                                    "scaffold.tower",
                                    "vanilla",
                                    Scaffold.towerOptions(),
                                    Scaffold::setTower)
                            .visibleWhen(Scaffold::tellySelected),
                    bool(
                            "block_counter",
                            "Block count",
                            "scaffold.blockCounter",
                            true,
                            Scaffold::setBlockCounter),
                });
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withHudTag(Scaffold::modeName);
    }
}
