package com.blanoir.tempos.client.module.impl.misc;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.input.InputKeys;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleKeybinds;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Third-person orbit without changing player rotation, movement, or outgoing aim. */
@ModuleInfo(
        category = ModuleCategory.MISC,
        description = "Rotates the third-person camera independently while you move.")
public final class FreeLook extends ClientFeature {
    public static final FreeLook INSTANCE = new FreeLook();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("freelook.enabled").defaultValue(false).build();
    private final BooleanSetting hold =
            new BooleanSetting.Builder().name("freelook.hold").defaultValue(true).build();
    private final FreeLookAngles angles = new FreeLookAngles();
    public final IntSetting transitionMs =
            new IntSetting.Builder()
                    .name("freelook.transitionMs")
                    .defaultValue(220)
                    .range(0, 1000)
                    .build();
    private final FreeLookTransition transition = new FreeLookTransition();
    private LocalPlayer player;
    private ClientLevel level;
    private CameraType previousPerspective;
    private boolean returning;
    private float returnYaw;
    private float returnPitch;
    private float renderedYaw;
    private float renderedPitch;
    private float returnProgress;

    private FreeLook() {
        super("freelook", "FreeLook");
    }

    @Override
    public void registerEvents() {
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

    private void update(Minecraft client) {
        if (!this.enabled.get() && this.player == null) return;
        if (!ready(client)) {
            // A GUI toggle can arm the feature; an interrupted active orbit always stops.
            if (this.player != null) reset(client);
            return;
        }
        if (this.player != null
                && (this.player != client.player
                        || this.level != client.level
                        || client.options.getCameraType() != CameraType.THIRD_PERSON_BACK)) {
            reset(client);
            return;
        }
        long now = System.nanoTime();
        if (!this.enabled.get()) {
            if (this.player == null) return;
            if (!this.returning) {
                this.returning = true;
                this.returnYaw = this.renderedYaw;
                this.returnPitch = this.renderedPitch;
                this.transition.begin(
                        now,
                        this.transitionMs.get(),
                        this.transition.distance(now),
                        this.previousPerspective.isFirstPerson() ? 0 : 1);
            }
            if (this.transition.finished(now)) restore(client);
            return;
        }
        var key = ModuleKeybinds.getBoundKey("freelook");
        if (this.hold.get() && (!InputKeys.isValid(key) || !PhysicalInput.isDown(client, key))) {
            setEnabled(client, false);
            return;
        }
        if (this.player != null) {
            if (this.returning) {
                this.returning = false;
                this.angles.begin(this.renderedYaw, this.renderedPitch);
                this.transition.begin(
                        now, this.transitionMs.get(), this.transition.distance(now), 1);
            }
            return;
        }
        this.player = client.player;
        this.level = client.level;
        this.previousPerspective = client.options.getCameraType();
        var camera = MinecraftClientAccess.camera(client);
        this.renderedYaw = camera.yRot();
        this.renderedPitch = camera.xRot();
        this.angles.begin(this.renderedYaw, this.renderedPitch);
        this.transition.begin(
                now, this.transitionMs.get(), this.previousPerspective.isFirstPerson() ? 0 : 1, 1);
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /** Entity.turn hook: cancel only the local player's vanilla turn while orbiting. */
    public boolean turn(Object entity, double horizontal, double vertical) {
        Minecraft client = Minecraft.getInstance();
        if (entity != client.player || !client.isSameThread()) return false;
        update(client);
        if (entity != this.player || this.player == null || !this.enabled.get() || !ready(client))
            return false;
        this.angles.turn(horizontal, vertical);
        return true;
    }

    /** Rewrites Camera.setRotation before vanilla calculates offset and collision distance. */
    public float cameraAngle(Object camera, float index, float original) {
        Minecraft client = Minecraft.getInstance();
        update(client);
        if (this.player == null
                || !ready(client)
                || camera != MinecraftClientAccess.camera(client)
                || client.options.getCameraType() != CameraType.THIRD_PERSON_BACK) return original;
        if (index == 0) {
            this.returnProgress = this.transition.progress(System.nanoTime());
            float target = this.previousPerspective.isMirrored() ? original + 180 : original;
            this.renderedYaw =
                    this.returning
                            ? FreeLookTransition.yaw(this.returnYaw, target, this.returnProgress)
                            : this.angles.yaw();
            return this.renderedYaw;
        }
        float target = this.previousPerspective.isMirrored() ? -original : original;
        this.renderedPitch =
                this.returning
                        ? Mth.lerp(this.returnProgress, this.returnPitch, target)
                        : this.angles.pitch();
        return this.renderedPitch;
    }

    /** Scale before vanilla's camera collision test, including when Clip is enabled. */
    public float cameraDistance(Object camera, float original) {
        Minecraft client = Minecraft.getInstance();
        return this.player != null
                        && camera == MinecraftClientAccess.camera(client)
                        && ready(client)
                ? original * this.transition.distance(System.nanoTime())
                : original;
    }

    public boolean isCameraActive() {
        return this.enabled.get() || this.player != null;
    }

    private void restore(Minecraft client) {
        if (this.previousPerspective != null
                && client != null
                && client.options != null
                && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            client.options.setCameraType(this.previousPerspective);
        }
        this.player = null;
        this.level = null;
        this.previousPerspective = null;
        this.returning = false;
    }

    public void reset(Minecraft client) {
        this.enabled.set(false);
        restore(client);
    }

    public boolean isHoldMode() {
        return this.hold.get();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public String statusTag() {
        return this.hold.get() ? "Hold" : "Toggle";
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        update(client);
        return 1;
    }

    public int setTransitionMs(Minecraft client, int value) {
        this.transitionMs.set(value);
        return 1;
    }

    public int setHold(Minecraft client, boolean value) {
        this.hold.set(value);
        reset(client);
        return 1;
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            hold.describeStored("hold", "Hold key", this::setHold),
            this.transitionMs.describe(
                    "transition_ms", "Transition (ms)", 10, this::setTransitionMs)
        };
    }
}
