package com.blanoir.tempos.client.module.impl.render;

import static com.blanoir.tempos.client.module.framework.SettingFactory.customChoice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.number;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.feature.HitEstimateSettings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.api.hud.Animation;
import com.blanoir.tempos.client.api.ClientUiScreen;
import com.blanoir.tempos.client.api.hud.HudElements;
import com.blanoir.tempos.client.api.hud.Bounds;
import com.blanoir.tempos.client.utils.combat.damage.PlayerHitEstimator;
import com.blanoir.tempos.client.utils.player.PlayerHealthResolver;
import com.blanoir.tempos.client.utils.time.FrameClock;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Locale;
import java.util.UUID;

/** Target state provider shared by the HUD renderers and their layout editors. */
@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Shows target health and the estimated number of hits remaining.")
public final class TargetInfoHud extends ClientFeature implements HudElements.Element<HudElements.Target> {
    public static final TargetInfoHud INSTANCE = new TargetInfoHud();

    private static final int WIDTH = com.blanoir.tempos.client.ui.hud.TargetHudStyle.WIDTH;
    private static final int HEIGHT = com.blanoir.tempos.client.ui.hud.TargetHudStyle.HEIGHT;
    private static final long HOLD_NANOS = 650_000_000L;

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("targetinfo.enabled").defaultValue(true).build();
    private final IntSetting positionX =
            new IntSetting.Builder().name("targetinfo.x").defaultValue(20).range(0, 10000).build();
    private final IntSetting positionY =
            new IntSetting.Builder().name("targetinfo.y").defaultValue(24).range(0, 10000).build();
    private final DoubleSetting scale =
            new DoubleSetting.Builder()
                    .name("targetinfo.scale")
                    .defaultValue(1.0D)
                    .range(0.25D, 2.0D)
                    .build();

    private final FrameClock frameClock = new FrameClock();
    private Player retainedTarget;
    private UUID animatedTargetId;
    private long lastTargetNanos;
    private double visibility;
    private double displayedHealth = Double.NaN;
    private double formattedHealth = Double.NaN;
    private float formattedMaxHealth = Float.NaN;
    private String healthText = "";

    private TargetInfoHud() {
        super("targetinfo", "TargetInfo");
    }

    @Override
    public void registerEvents() {
        HudElements.TARGET.register(this);
    }

    /** Advances animation state once and returns immutable display data. */
    public HudElements.Target snapshot(boolean editing) {
        return createSnapshot(editing);
    }

    private HudElements.Target createSnapshot(boolean editing) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer == null
                || client.level == null
                || (!editing && !enabled.get())
                || (!editing
                        && MinecraftClientAccess.screen(client) instanceof ClientUiScreen)) {
            if (!editing) resetHiddenState();
            return HudElements.Target.HIDDEN;
        }

        double seconds = frameClock.nextDeltaSeconds();
        Player liveTarget = editing ? currentPlayer : findTarget(client);
        long now = System.nanoTime();
        if (liveTarget != null) retain(liveTarget, now);

        boolean holding = retainedTarget != null && now - lastTargetNanos <= HOLD_NANOS;
        double targetVisibility = editing || liveTarget != null || holding ? 1.0D : 0.0D;
        visibility =
                editing ? 1.0D : Animation.approach(visibility, targetVisibility, seconds, 9.5D);

        Player shown = editing ? currentPlayer : retainedTarget;
        if (shown == null || visibility < 0.004D) {
            if (!editing && targetVisibility == 0.0D) resetHiddenState();
            return HudElements.Target.HIDDEN;
        }

        float resolvedHealth = PlayerHealthResolver.resolve(shown);
        float maxHealth = PlayerHealthResolver.max(shown);
        if (editing
                || animatedTargetId == null
                || !animatedTargetId.equals(shown.getUUID())
                || !Double.isFinite(displayedHealth)) {
            animatedTargetId = shown.getUUID();
            displayedHealth = resolvedHealth;
        } else {
            displayedHealth = Animation.approach(displayedHealth, resolvedHealth, seconds, 7.5D);
        }

        Bounds bounds = currentBounds();
        double easedVisibility = editing ? 1.0D : easeOut(visibility);
        int alpha = (int) Math.round(255.0D * easedVisibility);
        float slide = editing ? 0.0F : (float) (-6.0D * (1.0D - easedVisibility));
        double ratio =
                maxHealth <= 0.0F
                        ? 0.0D
                        : Math.max(0.0D, Math.min(1.0D, displayedHealth / maxHealth));

        return new HudElements.Target(
                true,
                bounds,
                shown.getName().getString(),
                healthText(maxHealth),
                PlayerHitEstimator.text(
                        client, shown, resolvedHealth, HitEstimateSettings::criticalRate),
                ratio,
                healthColor(displayedHealth, maxHealth),
                alpha,
                slide,
                shown.getUUID());
    }

    private String healthText(float maxHealth) {
        double health = Math.max(0.0D, displayedHealth);
        if (Double.compare(health, formattedHealth) != 0
                || Float.compare(maxHealth, formattedMaxHealth) != 0) {
            healthText = String.format(Locale.ROOT, "%.1f / %.1f HP", health, maxHealth);
            formattedHealth = health;
            formattedMaxHealth = maxHealth;
        }
        return healthText;
    }

    private void resetHiddenState() {
        visibility = 0.0D;
        retainedTarget = null;
        animatedTargetId = null;
        displayedHealth = Double.NaN;
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
        LivingEntity auraTarget = Targeting.currentTarget(client);
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

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.enabled.set(enabled);
        ClientChat.send(client, "TargetInfo " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public Bounds currentBounds() {
        Minecraft client = Minecraft.getInstance();
        double scale = scale();
        double width = WIDTH * scale;
        double height = HEIGHT * scale;
        double left =
                Math.max(
                        0.0D,
                        Math.min(
                                this.positionX.get(),
                                client.getWindow().getGuiScaledWidth() - width));
        double top =
                Math.max(
                        0.0D,
                        Math.min(
                                this.positionY.get(),
                                client.getWindow().getGuiScaledHeight() - height));
        return new Bounds(left, top, width, height);
    }

    public void setEditorPosition(double left, double top, int screenWidth, int screenHeight) {
        double width = WIDTH * scale();
        double height = HEIGHT * scale();
        int x = (int) Math.round(Math.max(0.0D, Math.min(screenWidth - width, left)));
        int y = (int) Math.round(Math.max(0.0D, Math.min(screenHeight - height, top)));
        this.positionX.set(x);
        this.positionY.set(y);
    }

    public double scale() {
        return crispScale(this.scale.get());
    }

    public int setScale(Minecraft ignoredClient, double value) {
        this.scale.set(crispScale(value));
        return 1;
    }

    public void resizeForEditor(
            double scale, double left, double top, int screenWidth, int screenHeight) {
        this.scale.set(crispScale(scale));
        setEditorPosition(left, top, screenWidth, screenHeight);
    }

    public void resetEditorPosition() {
        this.positionX.set(20);
        this.positionY.set(24);
        this.scale.set(1.0D);
    }

    private static double crispScale(double requested) {
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        double physicalScale = Math.max(1.0D, Math.rint(requested * guiScale));
        return Math.min(2.0D, physicalScale / guiScale);
    }

    @Override
    protected String statusTag() {
        return HitEstimateSettings.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            scale.describeStored("scale", "Scale", .05, this::setScale),
            customChoice(
                            "hit_estimate",
                            "Hit estimate",
                            HitEstimateSettings::mode,
                            HitEstimateSettings.modeOptions(),
                            HitEstimateSettings::setMode)
                    .withDefault("normal_estimate"),
            customChoice(
                            "critical_source",
                            "Critical percent source",
                            HitEstimateSettings::criticalSource,
                            HitEstimateSettings.criticalSourceOptions(),
                            HitEstimateSettings::setCriticalSource)
                    .withDefault("recorded")
                    .visibleWhen(HitEstimateSettings::criticalEstimate),
            number(
                            "critical_percent",
                            "Critical percent",
                            "hitestimate.customCriticalPercent",
                            50,
                            0,
                            100,
                            1,
                            HitEstimateSettings::setCustomCriticalPercent)
                    .visibleWhen(
                            () ->
                                    HitEstimateSettings.criticalEstimate()
                                            && !HitEstimateSettings.recordedSource())
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor()
                .withDefaultEnabled(true)
                .withHudTag(() -> HitEstimateSettings.criticalEstimate() ? "Critical" : "Normal");
    }
}
