package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.input.InputKeys;
import com.blanoir.moons.client.input.PhysicalInput;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Third-person orbit without changing player rotation, movement, or outgoing aim. */
public final class FreeLook {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("freelook.enabled").defaultValue(false).build();
    private static final BooleanSetting HOLD =
            new BooleanSetting.Builder().name("freelook.hold").defaultValue(true).build();
    private static final FreeLookAngles ANGLES = new FreeLookAngles();
    public static final IntSetting TRANSITION_MS =
            new IntSetting.Builder()
                    .name("freelook.transitionMs")
                    .defaultValue(220)
                    .range(0, 1000)
                    .build();
    private static final FreeLookTransition TRANSITION = new FreeLookTransition();
    private static LocalPlayer player;
    private static ClientLevel level;
    private static CameraType previousPerspective;
    private static boolean returning;
    private static float returnYaw;
    private static float returnPitch;
    private static float renderedYaw;
    private static float renderedPitch;
    private static float returnProgress;

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
        if (!ENABLED.get() && player == null) return;
        if (!ready(client)) {
            // A GUI toggle can arm the feature; an interrupted active orbit always stops.
            if (player != null) reset(client);
            return;
        }
        if (player != null
                && (player != client.player
                        || level != client.level
                        || client.options.getCameraType() != CameraType.THIRD_PERSON_BACK)) {
            reset(client);
            return;
        }
        long now = System.nanoTime();
        if (!ENABLED.get()) {
            if (player == null) return;
            if (!returning) {
                returning = true;
                returnYaw = renderedYaw;
                returnPitch = renderedPitch;
                TRANSITION.begin(
                        now,
                        TRANSITION_MS.get(),
                        TRANSITION.distance(now),
                        previousPerspective.isFirstPerson() ? 0 : 1);
            }
            if (TRANSITION.finished(now)) restore(client);
            return;
        }
        var key = ModuleKeybinds.getBoundKey("freelook");
        if (HOLD.get() && (!InputKeys.isValid(key) || !PhysicalInput.isDown(client, key))) {
            setEnabled(client, false);
            return;
        }
        if (player != null) {
            if (returning) {
                returning = false;
                ANGLES.begin(renderedYaw, renderedPitch);
                TRANSITION.begin(now, TRANSITION_MS.get(), TRANSITION.distance(now), 1);
            }
            return;
        }
        player = client.player;
        level = client.level;
        previousPerspective = client.options.getCameraType();
        var camera = MinecraftClientAccess.camera(client);
        renderedYaw = camera.yRot();
        renderedPitch = camera.xRot();
        ANGLES.begin(renderedYaw, renderedPitch);
        TRANSITION.begin(now, TRANSITION_MS.get(), previousPerspective.isFirstPerson() ? 0 : 1, 1);
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /** Entity.turn hook: cancel only the local player's vanilla turn while orbiting. */
    public static boolean turn(Object entity, double horizontal, double vertical) {
        Minecraft client = Minecraft.getInstance();
        if (entity != client.player || !client.isSameThread()) return false;
        update(client);
        if (entity != player || player == null || !ENABLED.get() || !ready(client)) return false;
        ANGLES.turn(horizontal, vertical);
        return true;
    }

    /** Rewrites Camera.setRotation before vanilla calculates offset and collision distance. */
    public static float cameraAngle(Object camera, float index, float original) {
        Minecraft client = Minecraft.getInstance();
        update(client);
        if (player == null
                || !ready(client)
                || camera != MinecraftClientAccess.camera(client)
                || client.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return original;
        if (index == 0) {
            returnProgress = TRANSITION.progress(System.nanoTime());
            float target = previousPerspective.isMirrored() ? original + 180 : original;
            renderedYaw =
                    returning
                            ? FreeLookTransition.yaw(returnYaw, target, returnProgress)
                            : ANGLES.yaw();
            return renderedYaw;
        }
        float target = previousPerspective.isMirrored() ? -original : original;
        renderedPitch = returning ? Mth.lerp(returnProgress, returnPitch, target) : ANGLES.pitch();
        return renderedPitch;
    }

    /** Scale before vanilla's camera collision test, including when Clip is enabled. */
    public static float cameraDistance(Object camera, float original) {
        Minecraft client = Minecraft.getInstance();
        return player != null && camera == MinecraftClientAccess.camera(client) && ready(client)
                ? original * TRANSITION.distance(System.nanoTime())
                : original;
    }

    public static boolean isCameraActive() {
        return ENABLED.get() || player != null;
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
        returning = false;
    }

    public static void reset(Minecraft client) {
        ENABLED.set(false);
        restore(client);
    }

    public static boolean isHoldMode() {
        return HOLD.get();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusTag() {
        return HOLD.get() ? "Hold" : "Toggle";
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        update(client);
        return 1;
    }

    public static int setTransitionMs(Minecraft client, int value) {
        TRANSITION_MS.set(value);
        return 1;
    }

    public static int setHold(Minecraft client, boolean value) {
        HOLD.set(value);
        reset(client);
        return 1;
    }
}
