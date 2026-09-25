package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;

public final class FreeLookVerification {
    public static void main(String[] args) {
        var angles = new FreeLookAngles();
        angles.begin(175, 0);
        angles.turn(100, -200);
        close(angles.yaw(), -170, "Yaw wraps across the seam");
        close(angles.pitch(), -30, "Mouse sensitivity matches vanilla turn");
        angles.turn(20, 10000);
        close(angles.yaw(), -167, "Yaw continues when pitch reaches a pole");
        close(angles.pitch(), 90, "Pitch cannot flip over the pole");
        angles.turn(0, -20);
        close(angles.pitch(), 87, "Pitch immediately moves away from the pole");
        angles.turn(Double.NaN, 100);
        angles.turn(100, Double.POSITIVE_INFINITY);
        close(angles.yaw(), -167, "Invalid deltas do not corrupt yaw");
        close(angles.pitch(), 87, "Invalid deltas do not corrupt pitch");
        angles.begin(721, -40);
        close(angles.yaw(), 1, "Reactivation starts from current player yaw");
        close(angles.pitch(), -40, "Reactivation forgets the last orbit pitch");

        Settings.remove("keybind.freelook");
        if (!ModuleKeybinds.getBoundKey("freelook").getName().equals("key.keyboard.left.alt"))
            throw new AssertionError("FreeLook defaults to Left Alt");
        ModuleKeybinds.unbind("freelook");
        if (ModuleKeybinds.isValid(ModuleKeybinds.getBoundKey("freelook")))
            throw new AssertionError("Clearing the key must suppress the default");
        Settings.setString("keybind.freelook", "key.keyboard.v");
        if (!ModuleKeybinds.getBoundKey("freelook").getName().equals("key.keyboard.v"))
            throw new AssertionError("Custom bindings override the default");
        System.out.println("MOONS_FREELOOK_VERIFIED");
    }

    private static void close(float actual, float expected, String message) {
        if (Math.abs(actual - expected) > .001f) throw new AssertionError(message + ": " + actual);
    }
}
