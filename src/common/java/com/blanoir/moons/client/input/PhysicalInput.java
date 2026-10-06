package com.blanoir.moons.client.input;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.compat.input.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/** Hardware queries stay independent of vanilla key mappings modified by synthetic input. */
public final class PhysicalInput {
    private PhysicalInput() {}

    /** Binding polling includes the existing Windows/SDL right-Shift fallback. */
    public static boolean isDown(Minecraft client, InputConstants.Key key) {
        return client != null
                && org.lwjgl.opengl.Display.isActive()
                && InputKeys.isValid(key)
                && MinecraftClientAccess.isBindingKeyDown(client, key);
    }

    /** Gameplay queries preserve the existing physical input policy and platform API. */
    public static boolean isGameplayKeyDown(Minecraft client, KeyBinding mapping) {
        if (client == null
                || mapping == null
                || MinecraftClientAccess.screen(client) != null
                || client.thePlayer == null
                || client.theWorld == null
                || client.playerController == null) return false;
        InputConstants.Key key = GameAccess.boundKey(mapping);
        return key != null
                && key != InputConstants.UNKNOWN
                && key.getValue() >= 0
                && MinecraftClientAccess.isHardwareKeyDown(client, key);
    }
}
