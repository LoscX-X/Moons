package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.ui.animation.UiMotion;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.blanoir.moons.client.ui.layout.Bounds;
import com.blanoir.moons.client.utils.combat.damage.PlayerHitEstimator;
import com.blanoir.moons.client.utils.player.PlayerHealthResolver;
import com.blanoir.moons.client.utils.time.FrameClock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Locale;
import java.util.UUID;

/** Target state provider for the independent final-frame Skia HUD layer. */
public final class TargetInfoHud {
    private static final TargetInfoHud INSTANCE = new TargetInfoHud();
    private static final int WIDTH = 166;
    private static final int HEIGHT = 63;
    private static final long HOLD_NANOS = 650_000_000L;

    private static final BooleanSetting ENABLED = new BooleanSetting.Builder()
            .name("targetinfo.enabled").defaultValue(true).build();
    private static final IntSetting POSITION_X = new IntSetting.Builder()
            .name("targetinfo.x").defaultValue(20).range(0, 10000).build();
    private static final IntSetting POSITION_Y = new IntSetting.Builder()
            .name("targetinfo.y").defaultValue(24).range(0, 10000).build();
    private static final DoubleSetting SCALE = new DoubleSetting.Builder()
            .name("targetinfo.scale").defaultValue(1.0D).range(0.25D, 2.0D).build();

    private final FrameClock frameClock = new FrameClock();
    private Player retainedTarget;
    private UUID animatedTargetId;
    private long lastTargetNanos;
    private double visibility;
    private double displayedHealth = Double.NaN;
    private Bounds lastBounds = new Bounds(0, 0, 0, 0);

    private TargetInfoHud() {
    }

    public static void init() {
        // Rendering is intentionally owned by TextGuiSkiaOverlay. Do not register HUD_RENDER here.
    }

    /** Advances animation state once and returns immutable data for the Skia renderer. */
    public static Snapshot snapshot(boolean editing) {
        return INSTANCE.createSnapshot(editing);
    }

    private Snapshot createSnapshot(boolean editing) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || (!editing && !ENABLED.get())
                || (!editing && MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen)) {
            if (!editing) resetHiddenState();
            return Snapshot.HIDDEN;
        }

        double seconds = frameClock.nextDeltaSeconds();
        Player liveTarget = editing ? client.player : findTarget(client);
        long now = System.nanoTime();
        if (liveTarget != null) retain(liveTarget, now);

        boolean holding = retainedTarget != null && now - lastTargetNanos <= HOLD_NANOS;
        double targetVisibility = editing || liveTarget != null || holding ? 1.0D : 0.0D;
        visibility = editing ? 1.0D : UiMotion.approach(visibility, targetVisibility, seconds, 9.5D);

        Player shown = editing ? client.player : retainedTarget;
        if (shown == null || visibility < 0.004D) {
            if (!editing && targetVisibility == 0.0D) resetHiddenState();
            return Snapshot.HIDDEN;
        }

        float resolvedHealth = PlayerHealthResolver.resolve(shown);
        float maxHealth = PlayerHealthResolver.max(shown);
        if (editing || animatedTargetId == null || !animatedTargetId.equals(shown.getUUID())
                || !Double.isFinite(displayedHealth)) {
            animatedTargetId = shown.getUUID();
            displayedHealth = resolvedHealth;
        } else {
            displayedHealth = UiMotion.approach(displayedHealth, resolvedHealth, seconds, 7.5D);
        }

        Bounds bounds = currentBounds();
        lastBounds = bounds;
        double easedVisibility = editing ? 1.0D : easeOut(visibility);
        int alpha = (int) Math.round(255.0D * easedVisibility);
        float slide = editing ? 0.0F : (float) (-6.0D * (1.0D - easedVisibility));
        double ratio = maxHealth <= 0.0F ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, displayedHealth / maxHealth));

        return new Snapshot(
                true,
                bounds,
                shown.getName().getString(),
                String.format(Locale.ROOT, "%.1f / %.1f HP", Math.max(0.0D, displayedHealth), maxHealth),
                PlayerHitEstimator.text(client, shown, resolvedHealth),
                ratio,
                healthColor(displayedHealth, maxHealth),
                alpha,
                slide
        );
    }

    private void resetHiddenState() {
        visibility = 0.0D;
        retainedTarget = null;
        animatedTargetId = null;
        displayedHealth = Double.NaN;
        lastBounds = new Bounds(0, 0, 0, 0);
        frameClock.reset();
    }

    private void retain(Player player, long now) {
        if (retainedTarget == null || !retainedTarget.getUUID().equals(player.getUUID())) {
            retainedTarget = player;
            animatedTargetId = null;
            displayedHealth = Double.NaN;
        } else {
            retainedTarget = player;
        }
        lastTargetNanos = now;
    }

    private static Player findTarget(Minecraft client) {
        LivingEntity auraTarget = SilentAura.currentTarget(client);
        if (auraTarget instanceof Player player && Targeting.isEnemyPlayer(client, player)) {
            return player;
        }
        if (client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof Player player
                && Targeting.isEnemyPlayer(client, player)) {
            return player;
        }
        return null;
    }

    private static int healthColor(double health, float maxHealth) {
        double ratio = maxHealth <= 0.0F ? 0.0D : health / maxHealth;
        return ratio > 0.6D ? 0x70D69A : ratio > 0.3D ? 0xE0B75B : 0xEB6D79;
    }

    private static double easeOut(double value) {
        double t = Math.max(0.0D, Math.min(1.0D, value));
        return 1.0D - (1.0D - t) * (1.0D - t);
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        ENABLED.set(enabled);
        ClientChat.send(client, "TargetInfo " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static Bounds currentBounds() {
        Minecraft client = Minecraft.getInstance();
        double scale = scale();
        double width = WIDTH * scale;
        double height = HEIGHT * scale;
        double left = Math.max(0.0D,
                Math.min(POSITION_X.get(), client.getWindow().getGuiScaledWidth() - width));
        double top = Math.max(0.0D,
                Math.min(POSITION_Y.get(), client.getWindow().getGuiScaledHeight() - height));
        return new Bounds(left, top, width, height);
    }

    public static void setEditorPosition(double left, double top, int screenWidth, int screenHeight) {
        double width = WIDTH * scale();
        double height = HEIGHT * scale();
        int x = (int) Math.round(Math.max(0.0D, Math.min(screenWidth - width, left)));
        int y = (int) Math.round(Math.max(0.0D, Math.min(screenHeight - height, top)));
        POSITION_X.set(x);
        POSITION_Y.set(y);
        INSTANCE.lastBounds = new Bounds(x, y, width, height);
    }

    public static double scale() {
        return crispScale(SCALE.get());
    }

    public static int setScale(Minecraft client, double value) {
        SCALE.set(crispScale(value));
        return 1;
    }

    public static void resizeForEditor(double scale, double left, double top,
                                       int screenWidth, int screenHeight) {
        SCALE.set(crispScale(scale));
        setEditorPosition(left, top, screenWidth, screenHeight);
    }

    public static void resetEditorPosition() {
        POSITION_X.set(20);
        POSITION_Y.set(24);
        SCALE.set(1.0D);
    }

    private static double crispScale(double requested) {
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        double physicalScale = Math.max(1.0D, Math.rint(requested * guiScale));
        return Math.min(2.0D, physicalScale / guiScale);
    }

    public record Snapshot(boolean visible, Bounds bounds, String name, String healthText,
                           String hitText, double healthRatio, int healthColor,
                           int alpha, float slide) {
        private static final Snapshot HIDDEN = new Snapshot(false,
                new Bounds(0, 0, 0, 0), "", "", "", 0.0D, 0x70D69A, 0, 0.0F);
    }
}
