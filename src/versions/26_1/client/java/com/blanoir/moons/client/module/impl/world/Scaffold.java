package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.module.world.scaffold.ScaffoldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class Scaffold {
    private Scaffold() { }

    public static void init() { ScaffoldEngine.init(); }
    public static boolean isEnabled() { return ScaffoldEngine.enabled(); }
    public static int setEnabled(Minecraft c, boolean v) { return ScaffoldEngine.setEnabled(c, v); }
    public static int showStatus(Minecraft c) { return ScaffoldEngine.showStatus(c); }
    public static String statusTag() { return ScaffoldEngine.statusTag(); }
    public static int setMode(Minecraft c, String v) { return ScaffoldEngine.setMode(c, v); }
    public static List<String> modeOptions() { return ScaffoldEngine.modeOptions(); }
    public static boolean legitSelected() { return ScaffoldEngine.legitSelected(); }
    public static boolean tellySelected() { return ScaffoldEngine.tellySelected(); }
    public static boolean smoothTellySelected() { return ScaffoldEngine.smoothTellySelected(); }
    public static boolean tellyBpsLimitSelected() { return ScaffoldEngine.tellyBpsLimitSelected(); }
    public static int setLegitDelay(Minecraft c, int min, int max) { return ScaffoldEngine.setLegitDelay(c, min, max); }
    public static int setTellyStartSpeed(Minecraft c, double v) { return ScaffoldEngine.setTellyStartSpeed(c, v); }
    public static int setTellyTrackSpeed(Minecraft c, double v) { return ScaffoldEngine.setTellyTrackSpeed(c, v); }
    public static int setTellyRotation(Minecraft c, String v) { return ScaffoldEngine.setTellyRotation(c, v); }
    public static List<String> tellyRotationOptions() { return ScaffoldEngine.tellyRotationOptions(); }
    public static int setTellyDelayMode(Minecraft c, String v) { return ScaffoldEngine.setTellyDelayMode(c, v); }
    public static List<String> tellyDelayModeOptions() { return ScaffoldEngine.tellyDelayModeOptions(); }
    public static int setFaceSampling(Minecraft c, String v) { return ScaffoldEngine.setFaceSampling(c, v); }
    public static List<String> faceSamplingOptions() { return ScaffoldEngine.faceSamplingOptions(); }
    public static int setTellyPlaceAngle(Minecraft c, double v) { return ScaffoldEngine.setTellyPlaceAngle(c, v); }
    public static int setTellyMaxForwardBlocks(Minecraft c, int v) { return ScaffoldEngine.setTellyMaxForwardBlocks(c, v); }
    public static int setTellyPlaceDelay(Minecraft c, int v) { return ScaffoldEngine.setTellyPlaceDelay(c, v); }
    public static int setTellyBlocksPerSecondEnabled(Minecraft c, boolean v) { return ScaffoldEngine.setTellyBlocksPerSecondEnabled(c, v); }
    public static int setTellyBlocksPerSecond(Minecraft c, int min, int max) { return ScaffoldEngine.setTellyBlocksPerSecond(c, min, max); }
    public static int setTellyFlat(Minecraft c, boolean v) { return ScaffoldEngine.setTellyFlat(c, v); }
    public static int setTellyFallRescue(Minecraft c, boolean v) { return ScaffoldEngine.setTellyFallRescue(c, v); }
    public static int setMoveFix(Minecraft c, String v) { return ScaffoldEngine.setMoveFix(c, v); }
    public static List<String> moveFixOptions() { return ScaffoldEngine.moveFixOptions(); }
    public static int setSprintMode(Minecraft c, String v) { return ScaffoldEngine.setSprintMode(c, v); }
    public static List<String> sprintModeOptions() { return ScaffoldEngine.sprintModeOptions(); }
    public static int setTower(Minecraft c, String v) { return ScaffoldEngine.setTower(c, v); }
    public static List<String> towerOptions() { return ScaffoldEngine.towerOptions(); }
    public static int setBlockCounter(Minecraft c, boolean v) { return ScaffoldEngine.setBlockCounter(c, v); }
    public static boolean shouldApplyRotation() { return ScaffoldEngine.shouldApplyRotation(); }
    public static boolean shouldCorrectMovement() { return ScaffoldEngine.shouldCorrectMovement(); }
    public static boolean shouldSuppressSprint(Minecraft c) { return ScaffoldEngine.shouldSuppressSprint(c); }
    public static float getYaw() { return ScaffoldEngine.yaw(); }
    public static float getPitch() { return ScaffoldEngine.pitch(); }
    public static float getRenderYaw() { return ScaffoldEngine.renderYaw(); }
    public static float getRenderPitch() { return ScaffoldEngine.renderPitch(); }
    public static float getMovementYaw() { return ScaffoldEngine.movementYaw(); }
    public static com.blanoir.moons.client.utils.rotation.Rotation getPacketRotation() {
        return ScaffoldEngine.packetRotation();
    }
    public static boolean cancelManualActions() { return ScaffoldEngine.cancelManualActions(); }
    public static boolean cancelUseAction() { return ScaffoldEngine.cancelUseAction(); }
    public static boolean handleHotbarSwap(int slot, int offset) { return ScaffoldEngine.handleHotbarSwap(slot, offset); }
    public static ItemStack spoofedItem(ItemStack original) { return ScaffoldEngine.spoofedItem(original); }

    // Debug
    public static int setDebugger(Minecraft c, boolean v) { return ScaffoldEngine.setDebugger(c, v); }
}
