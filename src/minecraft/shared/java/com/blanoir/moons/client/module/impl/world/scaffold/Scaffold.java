package com.blanoir.moons.client.module.impl.world.scaffold;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class Scaffold {
    private Scaffold() {}

    public static void init() {
        ScaffoldManager.init();
    }

    public static boolean isEnabled() {
        return ScaffoldManager.enabled();
    }

    public static int setEnabled(Minecraft c, boolean v) {
        return ScaffoldManager.setEnabled(c, v);
    }

    public static int showStatus(Minecraft c) {
        return ScaffoldManager.showStatus(c);
    }

    public static String statusTag() {
        return ScaffoldManager.statusTag();
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

    public static int setTellyRotation(Minecraft c, String v) {
        return ScaffoldManager.setTellyRotation(c, v);
    }

    public static List<String> tellyRotationOptions() {
        return ScaffoldManager.tellyRotationOptions();
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

    public static com.blanoir.moons.client.utils.rotation.Rotation getPacketRotation() {
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

    // Debug
    public static int setDebugger(Minecraft c, boolean v) {
        return ScaffoldManager.setDebugger(c, v);
    }
}
