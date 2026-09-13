package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.action.AttackInputEvent;
import com.blanoir.moons.client.event.network.PacketThread;
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
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Legacy blocking with a bounded replay of manual attack input. */
public final class AutoBlock {
    private enum Phase {
        IDLE,
        BLOCKING,
        UNBLOCKING,
        REBLOCKING
    }

    private static final BooleanSetting ENABLED = bool("autoblock.enabled", false);
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
    private static Phase phase = Phase.IDLE;
    private static int readyTick;
    private static int attackWindowExpires;
    private static int blockedTick = Integer.MIN_VALUE;
    private static int releasedTick = Integer.MIN_VALUE;
    private static int attackedTick = Integer.MIN_VALUE;
    private static int tickId;
    private static boolean pendingManualAttack;
    private static boolean suppressingAttack;
    private static InputConstants.Key pendingAttackKey;
    private static int pendingAttackSlot = -1;

    private AutoBlock() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "AutoBlock.context", event -> discard(event.client()));
        // This must precede Minecraft.handleKeybinds, which discards attack clicks during use.
        EventBus.TICK.register(
                "AutoBlock.tick", EventPriority.HIGHEST, event -> tick(event.client()));
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
                    if (completed && event.attacker() == client.player && canRun(client)) {

                        if (VISUAL.get()) BLOCK_HIT.start();
                        phase = Phase.REBLOCKING;
                        attackedTick = tickId;
                        readyTick = tickId + REBLOCK_TICKS.get();
                    }
                });
        EventBus.USE_INPUT_PRE.register(
                "AutoBlock.use",
                EventPriority.HIGHEST,
                event -> {
                    if (SilentAura.isActivationHeld(event.client())) {
                        reset(event.client());
                        return;
                    }
                    if (SilentPacketRotation.isInvokingSimulatedUse()
                            || USE.owned() && !USE.matches(event.client())) {
                        reset(event.client());
                    } else if (USE.owned()
                            || phase != Phase.IDLE
                            || ClientReady.world(event.client()) && releasedTick == tickId) {
                        // A held physical use key must not restart use inside our attack window.
                        event.cancel();
                    }
                });
        EventBus.PACKET_SEND_POST.register(
                "AutoBlock.actionBoundary",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!ENABLED.get()
                            || event.thread() != PacketThread.CLIENT
                            || !ClientReady.world(client)
                            || client.getConnection() == null
                            || event.connection() != client.getConnection().getConnection()) return;
                    // Include native/manual actions as well as requests issued by this module.
                    if (event.packet() instanceof ServerboundPlayerActionPacket action
                            && action.getAction()
                                    == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM)
                        releasedTick = tickId;
                    else if (event.packet() instanceof ServerboundAttackPacket)
                        attackedTick = tickId;
                });
    }

    private static void tick(Minecraft client) {
        tickId++;
        if (!refresh(client)) return;
        if (pendingManualAttack
                && pendingAttackSlot != client.player.getInventory().getSelectedSlot())
            clearPendingAttack(client);
        if (USE.owned() || phase != Phase.IDLE) captureAttackInput(client);
        if (!pendingManualAttack) return;
        if (!attackReady(client)) {
            suppressingAttack = true;
            CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_BLOCK);
            return;
        }
        InputConstants.Key key = pendingAttackKey;
        clearPendingAttack(client);
        // Re-submit only the input that was withheld. Vanilla chooses the current crosshair
        // target and dispatches it in handleKeybinds; no stored target or direct attack packet.
        if (key != null
                && key != InputConstants.UNKNOWN
                && key.equals(GameAccess.boundKey(client.options.keyAttack))) KeyMapping.click(key);
    }

    private static void captureAttackInput(Minecraft client) {
        boolean clicked = false;
        while (client.options.keyAttack.consumeClick()) clicked = true;
        if (clicked) {
            pendingManualAttack = true;
            pendingAttackKey = GameAccess.boundKey(client.options.keyAttack);
            pendingAttackSlot = client.player.getInventory().getSelectedSlot();
        }
    }

    private static void clearPendingAttack(Minecraft client) {
        pendingManualAttack = false;
        pendingAttackKey = null;
        pendingAttackSlot = -1;
        if (suppressingAttack) {
            suppressingAttack = false;
            CombatInputController.releaseAttack(client, CombatInputController.Owner.AUTO_BLOCK);
        }
    }

    private static boolean canRun(Minecraft client) {
        return ENABLED.get()
                && ClientReady.aliveGameplay(client)
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && (!REQUIRE_RIGHT_CLICK.get()
                        || CombatInputController.isPhysicallyDown(client, client.options.keyUse))
                && !SilentAura.isActivationHeld(client);
    }

    private static LivingEntity target(Minecraft client) {
        if (!canRun(client)) return null;
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
        if (USE.refresh(client)) {
            releasedTick = tickId;
            phase = Phase.IDLE;
            blockedTick = Integer.MIN_VALUE;
            clearPendingAttack(client);
        }
        if (!canRun(client)
                || client.player.isUsingItem() && !USE.ownsNativeUse(client)
                || SilentPacketRotation.isBusy()
                || PlacementCoordinator.busy()
                || RotationLease.hasSilentRotation()) {
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
        if (SilentAura.isActivationHeld(client)) return;
        if (!active && releasedTick != tickId) return;
        if (!attackReady(client)) {
            event.cancel();
            if (active && !CombatInputController.isInvokingTargetAttack()) {
                pendingManualAttack = true;
                pendingAttackKey = GameAccess.boundKey(client.options.keyAttack);
                pendingAttackSlot = client.player.getInventory().getSelectedSlot();
            }
        }
    }

    private static boolean attackReady(Minecraft client) {
        int tick = tickId;
        if (tick == releasedTick || tick == blockedTick || tick == attackedTick) return false;
        if (phase == Phase.REBLOCKING && tick < readyTick) return false;
        if (USE.owned()) {
            if (USE.stop(client)) releasedTick = tick;
            phase = Phase.UNBLOCKING;
            readyTick = tick + UNBLOCK_TICKS.get();
            attackWindowExpires = readyTick + 2;
            return false;
        }
        return ClientReady.world(client)
                && !client.player.isUsingItem()
                && (phase != Phase.UNBLOCKING || tick >= readyTick);
    }

    private static void reblock(Minecraft client) {
        if (!refresh(client)) return;
        int tick = tickId;
        if (pendingManualAttack || tick == releasedTick || tick == attackedTick) return;
        if (phase == Phase.UNBLOCKING && tick < attackWindowExpires) return;
        if (phase == Phase.REBLOCKING && tick < readyTick || USE.owned()) return;
        if (!USE.canStart(client)) return;
        // Resolve the current action/movement window, never the preceding sent rotation.
        RotationManager.Decision decision = RotationManager.resolve();
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
        return ENABLED.get() && VISUAL.get() && USE.ownsNativeUse(client);
    }

    public static boolean attackAnimationOnly() {
        return ENABLED.get();
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
        if (USE.stop(client)) releasedTick = tickId;
        clearPendingAttack(client);
        phase = Phase.IDLE;
        blockedTick = Integer.MIN_VALUE;
        readyTick = attackWindowExpires = 0;
    }

    private static void discard(Minecraft client) {
        USE.discard();
        clearPendingAttack(client);
        BLOCK_HIT.reset();
        pendingAnimationTarget = null;
        blockedTick = releasedTick = Integer.MIN_VALUE;
        attackedTick = Integer.MIN_VALUE;
        phase = Phase.IDLE;
        readyTick = attackWindowExpires = 0;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String hudTag() {
        Minecraft client = Minecraft.getInstance();
        if (ClientReady.world(client) && !BlockingUse.supportsSwordBlock(client))
            return "Legacy (Experiment) / Unsupported";
        return "Legacy (Experiment) / " + (USE.ownsNativeUse(client) ? "Blocking" : "Ready");
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
                    }),
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
                    }),
            UNBLOCK_TICKS.describe(
                    "unblock_ticks",
                    "Wait before attack (ticks)",
                    1,
                    (client, value) -> {
                        reset(client);
                        UNBLOCK_TICKS.set(value);
                        return 1;
                    }),
            REBLOCK_TICKS.describe(
                    "reblock_ticks",
                    "Reblock delay (ticks)",
                    1,
                    (client, value) -> {
                        reset(client);
                        REBLOCK_TICKS.set(value);
                        return 1;
                    })
        };
    }

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder().name(key).defaultValue(value).build();
    }
}
