package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.action.AttackInputEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationManager;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.BlockingUse;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.render.AnimationPulse;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Blocking and release run before movement; completed entity attacks start one visual pulse. */
public final class AutoBlock {
    private enum Mode {
        LEGACY,
        LATEST
    }

    private enum Phase {
        IDLE,
        BLOCKING,
        UNBLOCKING,
        REBLOCKING
    }

    private static final BooleanSetting ENABLED =
            bool("autoblock.enabled", Settings.getBoolean("silentaura.block", true));
    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("autoblock.mode")
                    .defaultValue(Mode.LATEST)
                    .option(Mode.LEGACY, "legacy")
                    .option(Mode.LATEST, "latest")
                    .build();
    private static final BooleanSetting REQUIRE_AURA = bool("autoblock.requireAura", true);
    private static final BooleanSetting REQUIRE_RIGHT_CLICK =
            bool("autoblock.requireRightClick", false);
    private static final BooleanSetting VISUAL = bool("autoblock.visual", true);
    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("autoblock.range")
                    .defaultValue(4.5)
                    .range(1, 8)
                    .build();
    private static final DoubleSetting FOV =
            new DoubleSetting.Builder()
                    .name("autoblock.fov")
                    .defaultValue(360)
                    .range(1, 360)
                    .build();
    private static final IntSetting UNBLOCK_TICKS =
            new IntSetting.Builder()
                    .name("autoblock.unblockTicks")
                    .defaultValue(1)
                    .range(1, 5)
                    .build();
    private static final IntSetting REBLOCK_TICKS =
            new IntSetting.Builder()
                    .name("autoblock.reblockTicks")
                    .defaultValue(1)
                    .range(1, 5)
                    .build();
    private static final BlockingUse USE = new BlockingUse();
    private static final AnimationPulse BLOCK_HIT = new AnimationPulse(120);
    private static Entity pendingAnimationTarget;
    private static BooleanSupplier auraEnabled = () -> false;
    private static BooleanSupplier auraLegacy = () -> false;
    private static Predicate<Minecraft> auraActive = client -> false;
    private static Function<Minecraft, LivingEntity> auraTarget = client -> null;
    private static Phase phase = Phase.IDLE;
    private static int readyTick;
    private static int attackWindowExpires;
    private static int blockedTick = Integer.MIN_VALUE;
    private static int releasedTick = Integer.MIN_VALUE;
    private static boolean lastLegacy;

    private AutoBlock() {}

    public static void bindAura(
            BooleanSupplier enabled,
            BooleanSupplier legacy,
            Predicate<Minecraft> active,
            Function<Minecraft, LivingEntity> target) {
        auraEnabled = enabled;
        auraLegacy = legacy;
        auraActive = active;
        auraTarget = target;
    }

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoBlock.context", event -> discard());
        EventBus.TICK.register("AutoBlock.tick", event -> refresh(event.client()));
        EventBus.PLAYER_UPDATE.register(
                "AutoBlock.reblock",
                EventPriority.HIGHEST,
                event -> {
                    if (!event.isCancelled()) reblock(event.client());
                });
        EventBus.ATTACK_INPUT_PRE.register(
                "AutoBlock.beforeAttack", EventPriority.HIGHEST, AutoBlock::beforeAttack);
        EventBus.ATTACK_ENTITY_PRE.register(
                "AutoBlock.captureAttack",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    // Capture eligibility before damage so a killing attack can finish its
                    // animation.
                    pendingAnimationTarget =
                            event.attacker() == client.player
                                            && legacy()
                                            && target(client) != null
                                            && event.target() instanceof LivingEntity
                                            && CombatReach.within(
                                                    client, event.target(), RANGE.get())
                                    ? event.target()
                                    : null;
                });
        EventBus.ATTACK_ENTITY_POST.register(
                "AutoBlock.afterAttack",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    boolean completed = event.target() == pendingAnimationTarget;
                    pendingAnimationTarget = null;
                    if (completed
                            && event.attacker() == client.player
                            && legacy()
                            && canRun(client)) {
                        if (VISUAL.get()) BLOCK_HIT.start();
                        phase = Phase.REBLOCKING;
                        readyTick = client.player.tickCount + REBLOCK_TICKS.get();
                    }
                });
        EventBus.USE_INPUT_PRE.register(
                "AutoBlock.use",
                EventPriority.HIGHEST,
                event -> {
                    if (SilentPacketRotation.isInvokingSimulatedUse()
                            || USE.owned() && !USE.matches(event.client())) {
                        reset(event.client());
                    } else if (USE.owned()
                            || legacy() && phase != Phase.IDLE
                            || ClientReady.world(event.client())
                                    && releasedTick == event.client().player.tickCount) {
                        // A held physical use key must not restart use inside our attack window.
                        event.cancel();
                    }
                });
    }

    private static boolean legacy() {
        return auraEnabled.getAsBoolean() ? auraLegacy.getAsBoolean() : MODE.get() == Mode.LEGACY;
    }

    private static boolean canRun(Minecraft client) {
        return ENABLED.get()
                && ClientReady.aliveGameplay(client)
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && (!REQUIRE_RIGHT_CLICK.get()
                        || CombatInputController.isPhysicallyDown(client, client.options.keyUse))
                && (!REQUIRE_AURA.get() || auraActive.test(client));
    }

    private static LivingEntity target(Minecraft client) {
        if (!canRun(client)) return null;
        if (auraActive.test(client)) {
            LivingEntity target = auraTarget.apply(client);
            return target != null
                            && target.isAlive()
                            && client.level.getEntity(target.getId()) == target
                            && CombatReach.within(client, target, RANGE.get())
                    ? target
                    : null;
        }
        if (REQUIRE_AURA.get()) return null;
        LivingEntity closest = null;
        double distance = RANGE.get() * RANGE.get();
        for (var player : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, player)
                    || !client.player.hasLineOfSight(player)) continue;
            double candidate = EntityDistance.squaredToEntity(client, player);
            double angle =
                    MathUtils.angleBetween(
                            client.player.getViewVector(1),
                            player.getBoundingBox()
                                    .getCenter()
                                    .subtract(client.player.getEyePosition()));
            if (candidate <= distance && MathUtils.withinFov(angle, FOV.get())) {
                closest = player;
                distance = candidate;
            }
        }
        return closest;
    }

    private static boolean refresh(Minecraft client) {
        boolean legacy = legacy();
        if (lastLegacy != legacy) reset(client);
        lastLegacy = legacy;
        if (!legacy) return false;
        if (USE.owned() && !USE.matches(client)) discard();
        if (!canRun(client)
                || client.player.isUsingItem()
                || SilentPacketRotation.isBusy()
                || PlacementCoordinator.busy()
                || RotationLease.hasSilentRotation() && !auraActive.test(client)) {
            reset(client);
            return false;
        }
        if (target(client) == null) {
            resetBlocking(client);
            return false;
        }
        return true;
    }

    private static void beforeAttack(AttackInputEvent.Pre event) {
        Minecraft client = event.client();
        if (event.isCancelled()) return;
        boolean active = refresh(client);
        if (ClientReady.world(client) && releasedTick == client.player.tickCount) {
            event.cancel();
            return;
        }
        if (!active || phase == Phase.IDLE) return;
        // Finish the pending block phase before opening another attack window.
        if (phase == Phase.REBLOCKING) {
            event.cancel();
            return;
        }
        int tick = client.player.tickCount;
        if (USE.owned()) {
            // Do not emit USE and RELEASE in the same movement window.
            if (tick == blockedTick) {
                event.cancel();
                return;
            }
            if (USE.stop(client)) releasedTick = tick;
            phase = Phase.UNBLOCKING;
            readyTick = tick + UNBLOCK_TICKS.get();
            attackWindowExpires = readyTick + 2;
        }
        if (phase == Phase.UNBLOCKING && tick < readyTick) event.cancel();
    }

    private static void reblock(Minecraft client) {
        if (!refresh(client)) return;
        int tick = client.player.tickCount;
        if (tick == releasedTick) return;
        if (phase == Phase.UNBLOCKING && tick < attackWindowExpires) return;
        if (phase == Phase.REBLOCKING && tick < readyTick || USE.owned()) return;
        if (!USE.canStart(client)) return;
        // Resolve the current action/movement window, never the preceding sent rotation.
        RotationManager.Decision decision = RotationManager.resolve();
        if (auraActive.test(client) && decision == null) return;
        Rotation rotation =
                decision != null
                        ? decision.rotation()
                        : new Rotation(client.player.getYRot(), client.player.getXRot());
        if (!RotationLease.holdManual(rotation)) return;
        if (USE.start(client, rotation)) {
            phase = Phase.BLOCKING;
            blockedTick = tick;
        }
    }

    public static boolean shouldRenderBlock(Minecraft client) {
        // Keep the block pose between hits, including the release/reblock attack window.
        return (!legacy() || VISUAL.get()) && target(client) != null;
    }

    public static boolean attackAnimationOnly() {
        return ENABLED.get() && legacy();
    }

    public static double animationProgress() {
        return BLOCK_HIT.active() ? BLOCK_HIT.progress() : 0.0;
    }

    public static void reset(Minecraft client) {
        BLOCK_HIT.reset();
        pendingAnimationTarget = null;
        resetBlocking(client);
    }

    private static void resetBlocking(Minecraft client) {
        if (USE.stop(client)) releasedTick = client.player.tickCount;
        phase = Phase.IDLE;
        blockedTick = Integer.MIN_VALUE;
        readyTick = attackWindowExpires = 0;
    }

    private static void discard() {
        USE.discard();
        BLOCK_HIT.reset();
        pendingAnimationTarget = null;
        blockedTick = releasedTick = Integer.MIN_VALUE;
        phase = Phase.IDLE;
        readyTick = attackWindowExpires = 0;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String hudTag() {
        return legacy() ? "Legacy" : "Latest";
    }

    public static int setEnabled(Minecraft client, boolean value) {
        reset(client);
        ENABLED.set(value);
        ClientChat.send(client, "AutoBlock " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static ModuleRegistry.Setting[] settings() {
        return Descriptors.ALL.clone();
    }

    private static final class Descriptors {
        private static final ModuleRegistry.Setting[] ALL = {
            MODE.describe(
                            "mode",
                            "Mode",
                            (client, value) -> {
                                reset(client);
                                MODE.deserialize(value);
                                return 1;
                            })
                    .visibleWhen(() -> !auraEnabled.getAsBoolean()),
            REQUIRE_AURA.describe(
                    "require_aura",
                    "Require SilentAura",
                    (client, value) -> {
                        reset(client);
                        REQUIRE_AURA.set(value);
                        return 1;
                    }),
            REQUIRE_RIGHT_CLICK.describe(
                    "require_right_click",
                    "Require right click",
                    (client, value) -> {
                        reset(client);
                        REQUIRE_RIGHT_CLICK.set(value);
                        return 1;
                    }),
            VISUAL.describe(
                            "visual",
                            "Visual blocking",
                            (client, value) -> {
                                BLOCK_HIT.reset();
                                VISUAL.set(value);
                                return 1;
                            })
                    .visibleWhen(AutoBlock::legacy),
            RANGE.describe(
                    "range",
                    "Block range",
                    .1,
                    (client, value) -> {
                        reset(client);
                        RANGE.set(value);
                        return 1;
                    }),
            FOV.describe(
                            "fov",
                            "FOV",
                            1,
                            (client, value) -> {
                                reset(client);
                                FOV.set(value);
                                return 1;
                            })
                    .visibleWhen(() -> !REQUIRE_AURA.get()),
            UNBLOCK_TICKS
                    .describe(
                            "unblock_ticks",
                            "Wait before attack (ticks)",
                            1,
                            (client, value) -> {
                                reset(client);
                                UNBLOCK_TICKS.set(value);
                                return 1;
                            })
                    .visibleWhen(AutoBlock::legacy),
            REBLOCK_TICKS
                    .describe(
                            "reblock_ticks",
                            "Reblock delay (ticks)",
                            1,
                            (client, value) -> {
                                reset(client);
                                REBLOCK_TICKS.set(value);
                                return 1;
                            })
                    .visibleWhen(AutoBlock::legacy)
        };
    }

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder().name(key).defaultValue(value).build();
    }
}
