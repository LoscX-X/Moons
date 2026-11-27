package com.blanoir.tempos.client.module.impl.combat;

import static com.blanoir.tempos.client.module.framework.SettingFactory.number;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.combat.velocity.JumpReset;
import com.blanoir.tempos.client.module.impl.combat.velocity.VelocityPacketListener;
import com.blanoir.tempos.client.utils.math.PercentAccumulator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.stream.Collectors;

/** Normal knockback scaling and the original JumpReset behavior. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Adjusts knockback handling with scaling and jump-reset modes.")
public final class Velocity extends ClientFeature {
    private static final String MODE_KEY = "velocity.mode";
    public static final Velocity INSTANCE = new Velocity();

    public enum Mode {
        NORMAL,
        JUMP_RESET
    }

    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name(MODE_KEY)
                    .defaultValue(Mode.JUMP_RESET)
                    .option(Mode.NORMAL, "normal")
                    .option(Mode.JUMP_RESET, "jumpreset")
                    .build();
    private final DoubleSetting chance = percent("velocity.chance", 100.0D);
    private final DoubleSetting horizontal = percent("velocity.horizontal", 0.0D);
    private final DoubleSetting vertical = percent("velocity.vertical", 100.0D);
    private final DoubleSetting explosionHorizontal =
            percent("velocity.explosionHorizontal", 100.0D);
    private final DoubleSetting explosionVertical = percent("velocity.explosionVertical", 100.0D);
    private final BooleanSetting fakeCheck =
            new BooleanSetting.Builder().name("velocity.fakeCheck").defaultValue(true).build();
    private final BooleanSetting otherAttacks =
            new BooleanSetting.Builder().name("velocity.otherAttacks").defaultValue(false).build();
    private final JumpReset jumpReset = sub(new JumpReset(this));
    private volatile boolean active = jumpReset.isActive();
    private volatile PlayerSnapshot playerSnapshot = PlayerSnapshot.EMPTY;
    private boolean initialized;
    private boolean allowNext = true;
    private boolean pendingDamageKnown;
    private boolean pendingDamageAllowed;
    private final PercentAccumulator chanceSampler = new PercentAccumulator();

    private Velocity() {
        super("jumpreset", "Velocity");
    }

    public void registerJumpResetEvents() {
        jumpReset.registerEvents();
    }

    public void registerJumpResetPacketListeners() {
        jumpReset.initPacketListeners();
    }

    public void initPacketListeners() {
        EventBus.PACKET_RECEIVE_BUNDLE.register(
                "Velocity.bundleExpansion",
                event -> {
                    if (isEnabled() && normalMode()) event.requestExpansion();
                });
        VelocityPacketListener.init();
    }

    @Override
    public synchronized void registerEvents() {
        if (this.initialized) return;
        this.initialized = true;
        String storedMode = Settings.getString(MODE_KEY, "jumpreset").trim();
        if (storedMode.equalsIgnoreCase("vanilla")) this.mode.set(Mode.NORMAL);
        else if (storedMode.equalsIgnoreCase("jump") || storedMode.equalsIgnoreCase("grim2371")) {
            this.mode.set(Mode.JUMP_RESET);
        }
        jumpReset.bindModeCheck(this::jumpResetMode);
        EventBus.TICK.register("Velocity.tick", event -> snapshot(event.client()));
    }

    @Override
    public boolean isEnabled() {
        return this.active;
    }

    @Override
    public int setEnabled(Minecraft client, boolean enabled) {
        this.active = enabled;
        synchronized (this) {
            this.allowNext = true;
            clearPendingDamage();
        }
        return jumpReset.setActive(client, enabled);
    }

    @Override
    public String statusTag() {
        return normalMode() ? "Normal" : "JumpReset";
    }

    public int setMode(Minecraft client, String value) {
        // Preserve configurations exported before the ordinary mode was named Normal.
        this.mode.deserialize(
                value != null && value.trim().equalsIgnoreCase("vanilla") ? "normal" : value);
        synchronized (this) {
            this.allowNext = true;
            clearPendingDamage();
        }
        return 1;
    }

    public boolean normalMode() {
        return this.mode.get() == Mode.NORMAL;
    }

    public boolean jumpResetMode() {
        return this.mode.get() == Mode.JUMP_RESET;
    }

    public int setChance(Minecraft ignoredClient, double value) {
        this.chance.set(value);
        return 1;
    }

    public int setHorizontal(Minecraft ignoredClient, double value) {
        this.horizontal.set(value);
        return 1;
    }

    public int setVertical(Minecraft ignoredClient, double value) {
        this.vertical.set(value);
        return 1;
    }

    public int setExplosionHorizontal(Minecraft ignoredClient, double value) {
        this.explosionHorizontal.set(value);
        return 1;
    }

    public int setExplosionVertical(Minecraft ignoredClient, double value) {
        this.explosionVertical.set(value);
        return 1;
    }

    public int setFakeCheck(Minecraft ignoredClient, boolean value) {
        this.fakeCheck.set(value);
        synchronized (this) {
            this.allowNext = true;
            clearPendingDamage();
        }
        return 1;
    }

    public boolean fakeCheckEnabled() {
        return this.fakeCheck.get();
    }

    public int setOtherAttacks(Minecraft ignoredClient, boolean value) {
        this.otherAttacks.set(value);
        synchronized (this) {
            this.allowNext = true;
            clearPendingDamage();
        }
        return 1;
    }

    /** Records whether the damage source is a player before the following hurt/velocity packets. */
    public synchronized void handleDamageEvent(int entityId, int sourceCauseId) {
        PlayerSnapshot snapshot = this.playerSnapshot;
        if (!this.active || !normalMode() || entityId != snapshot.entityId()) return;
        this.pendingDamageKnown = true;
        this.pendingDamageAllowed =
                this.otherAttacks.get() || snapshot.playerEntityIds().contains(sourceCauseId);
    }

    /** Uses entity-status opcode 2 to gate fake-check. */
    public synchronized void handleEntityStatus(int entityId, byte eventId) {
        if (this.active
                && normalMode()
                && eventId == 2
                && entityId == this.playerSnapshot.entityId()) {
            // With fake-check enabled, only a matching accepted damage event arms
            // the next velocity. This makes player-only vs other attacks explicit.
            this.allowNext = !(this.pendingDamageKnown && this.pendingDamageAllowed);
            clearPendingDamage();
        }
    }

    /**
     * Returns a replacement set-motion vector, or {@code null} when vanilla should receive
     * the original packet. Chance is sampled through a deterministic accumulator.
     */
    public synchronized Vec3 transformEntityVelocity(int entityId, Vec3 incoming) {
        PlayerSnapshot snapshot = this.playerSnapshot;
        if (!this.active || !normalMode() || entityId != snapshot.entityId()) return null;
        if (this.fakeCheck.get() && this.allowNext) return null;

        this.allowNext = true;
        if (!this.chanceSampler.test(this.chance.get())) return null;

        Vec3 current = snapshot.movement();
        double horizontal = this.horizontal.get();
        double vertical = this.vertical.get();
        Vec3 transformed =
                new Vec3(
                        horizontal > 0.0D ? incoming.x * horizontal / 100.0D : current.x,
                        vertical > 0.0D ? incoming.y * vertical / 100.0D : current.y,
                        horizontal > 0.0D ? incoming.z * horizontal / 100.0D : current.z);
        return transformed.equals(incoming) ? null : transformed;
    }

    /**
     * Explosion motion is additive in modern vanilla. This scales the final velocity,
     * so convert that final value back into an additive packet vector for an equivalent result.
     */
    public synchronized Vec3 transformExplosionVelocity(Vec3 knockback) {
        if (!this.active || !normalMode()) return null;
        if (knockback.equals(Vec3.ZERO) || this.fakeCheck.get() && this.allowNext) return null;

        this.allowNext = true;
        Vec3 current = this.playerSnapshot.movement();
        double horizontal = this.explosionHorizontal.get();
        Vec3 transformed = getTransformed(knockback, horizontal, current);
        return transformed.equals(knockback) ? null : transformed;
    }

    private @NonNull Vec3 getTransformed(Vec3 knockback, double horizontal, Vec3 current) {
        double vertical = this.explosionVertical.get();
        double desiredX =
                horizontal > 0.0D ? (current.x + knockback.x) * horizontal / 100.0D : current.x;
        double desiredY =
                vertical > 0.0D ? (current.y + knockback.y) * vertical / 100.0D : current.y;
        double desiredZ =
                horizontal > 0.0D ? (current.z + knockback.z) * horizontal / 100.0D : current.z;
        return new Vec3(desiredX - current.x, desiredY - current.y, desiredZ - current.z);
    }

    private synchronized void snapshot(Minecraft client) {
        var currentLevel = client == null ? null : client.level;
        LocalPlayer player = client == null ? null : client.player;
        PlayerSnapshot previous = this.playerSnapshot;
        if (client == null || player == null) {
            this.playerSnapshot = PlayerSnapshot.EMPTY;
            this.allowNext = true;
            clearPendingDamage();
            return;
        }
        int entityId = player.getId();
        Set<Integer> playerIds =
                currentLevel == null
                        ? Set.of(entityId)
                        : currentLevel.players().stream()
                                .map(entity -> entity.getId())
                                .collect(Collectors.toUnmodifiableSet());
        this.playerSnapshot = new PlayerSnapshot(entityId, player.getDeltaMovement(), playerIds);
        if (previous.entityId() != entityId) {
            this.allowNext = true;
            clearPendingDamage();
        }
    }

    private static DoubleSetting percent(String key, double fallback) {
        return new DoubleSetting.Builder()
                .name(key)
                .defaultValue(fallback)
                .range(0.0D, 100.0D)
                .build();
    }

    private void clearPendingDamage() {
        this.pendingDamageKnown = false;
        this.pendingDamageAllowed = false;
    }

    private record PlayerSnapshot(int entityId, Vec3 movement, Set<Integer> playerEntityIds) {
        private static final PlayerSnapshot EMPTY =
                new PlayerSnapshot(Integer.MIN_VALUE, Vec3.ZERO, Set.of());
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            mode.describeStored("mode", "Mode", this::setMode),
            chance.describeStored("chance", "Chance %", 1, this::setChance)
                    .visibleWhen(this::normalMode),
            horizontal
                    .describeStored("horizontal", "Horizontal %", 1, this::setHorizontal)
                    .visibleWhen(this::normalMode),
            vertical.describeStored("vertical", "Vertical %", 1, this::setVertical)
                    .visibleWhen(this::normalMode),
            explosionHorizontal
                    .describeStored(
                            "explosion_horizontal",
                            "Explosion horizontal %",
                            1,
                            this::setExplosionHorizontal)
                    .visibleWhen(this::normalMode),
            explosionVertical
                    .describeStored(
                            "explosion_vertical",
                            "Explosion vertical %",
                            1,
                            this::setExplosionVertical)
                    .visibleWhen(this::normalMode),
            fakeCheck
                    .describeStored("fake_check", "Fake check", this::setFakeCheck)
                    .visibleWhen(this::normalMode),
            otherAttacks
                    .describeStored("other_attacks", "Other attacks", this::setOtherAttacks)
                    .visibleWhen(this::normalMode)
                    .enabledWhen(this::fakeCheckEnabled),
            number(
                            "jumpreset_chance",
                            "JumpReset chance",
                            "jumpreset.chance",
                            .5,
                            0,
                            1,
                            .01,
                            jumpReset::setChance)
                    .visibleWhen(this::jumpResetMode)
        };
    }
}
