package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** Third-person orbit without changing player rotation, movement, or outgoing aim. */
public final class FreeLook {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("freelook.enabled").defaultValue(false).build();
    private static final BooleanSetting HOLD =
            new BooleanSetting.Builder().name("freelook.hold").defaultValue(true).build();
    private static final FreeLookAngles ANGLES = new FreeLookAngles();
    private static LocalPlayer player;
    private static ClientLevel level;
    private static CameraType previousPerspective;

    private FreeLook() {}

    public static void init() {
        EventBus.FRAME.register("FreeLook.frame", EventPriority.HIGHEST, e -> update(e.client()));
        EventBus.TICK.register("FreeLook.tick", EventPriority.HIGHEST, e -> update(e.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("FreeLook.context", e -> reset(e.client()));
    }

    private static boolean ready(Minecraft client) {
        return ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && !client.player.isSleeping()
                && client.getCameraEntity() == client.player;
    }

    private static void update(Minecraft client) {
        if (!ENABLED.get()) {
            restore(client);
            return;
        }
        if (!ready(client)) {
            // A GUI toggle can arm the feature; an interrupted active orbit always stops.
            if (player != null) reset(client);
            return;
        }
        var key = ModuleKeybinds.getBoundKey("freelook");
        if (HOLD.get()
                && (!ModuleKeybinds.isValid(key)
                        || !MinecraftClientAccess.isBindingKeyDown(client, key))) {
            reset(client);
            return;
        }
        if (player != null) {
            if (player != client.player
                    || level != client.level
                    || client.options.getCameraType() != CameraType.THIRD_PERSON_BACK)
                reset(client);
            return;
        }
        player = client.player;
        level = client.level;
        previousPerspective = client.options.getCameraType();
        ANGLES.begin(player.getYRot(), player.getXRot());
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /** Entity.turn hook: cancel only the local player's vanilla turn while orbiting. */
    public static boolean turn(Object entity, double horizontal, double vertical) {
        Minecraft client = Minecraft.getInstance();
        if (entity != client.player || !client.isSameThread()) return false;
        update(client);
        if (entity != player || player == null || !ready(client)) return false;
        ANGLES.turn(horizontal, vertical);
        return true;
    }

    /** Rewrites Camera.setRotation before vanilla calculates offset and collision distance. */
    public static float cameraAngle(Object camera, float index, float original) {
        Minecraft client = Minecraft.getInstance();
        if (player == null
                || !ENABLED.get()
                || !ready(client)
                || camera != MinecraftClientAccess.camera(client)
                || client.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return original;
        return index == 0 ? ANGLES.yaw() : ANGLES.pitch();
    }

    private static void restore(Minecraft client) {
        if (previousPerspective != null
                && client != null
                && client.options != null
                && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            client.options.setCameraType(previousPerspective);
        }
        player = null;
        level = null;
        previousPerspective = null;
    }

    public static void reset(Minecraft client) {
        ENABLED.set(false);
        restore(client);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusTag() {
        return HOLD.get() ? "Hold" : "Toggle";
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) restore(client);
        return 1;
    }

    public static int setHold(Minecraft client, boolean value) {
        HOLD.set(value);
        reset(client);
        return 1;
    }
}
