package com.blanoir.tempos.client.module.impl.combat;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;

import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.action.AttackInputEvent;
import com.blanoir.tempos.client.event.network.PacketThread;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.manager.combat.BlockingUse;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.lease.RotationLease;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.rotation.RotationManager;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.entity.EntityDistance;
import com.blanoir.tempos.client.utils.math.ViewCone;
import com.blanoir.tempos.client.utils.render.AnimationPulse;
import com.blanoir.tempos.client.utils.rotation.Rotation;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Legacy blocking with a bounded replay of manual attack input. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Controls the timing of legacy sword blocking between attacks.")
public final class AutoBlock extends ClientFeature {
    public static final AutoBlock INSTANCE = new AutoBlock();

    private enum Phase {
        IDLE,
        BLOCKING,
        UNBLOCKING,
        REBLOCKING
    }

    private final BooleanSetting enabled = bool("autoblock.enabled", false);
    private final BooleanSetting requireRightClick = bool("autoblock.requireRightClick", false);
    private final BooleanSetting visual = bool("autoblock.visual", true);
    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("autoblock.range")
                    .defaultValue(4.5)
                    .range(1, 8)
                    .build();
    private final DoubleSetting fov =
            new DoubleSetting.Builder()
                    .name("autoblock.fov")
                    .defaultValue(360)
                    .range(1, 360)
                    .build();
    private final IntSetting unblockTicks =
            new IntSetting.Builder()
                    .name("autoblock.unblockTicks")
                    .defaultValue(1)
                    .range(1, 5)
                    .build();
    private final IntSetting reblockTicks =
            new IntSetting.Builder()
                    .name("autoblock.reblockTicks")
                    .defaultValue(1)
                    .range(1, 5)
                    .build();
    private final BlockingUse use = new BlockingUse();
    private final AnimationPulse blockHit = new AnimationPulse(120);
    private Entity pendingAnimationTarget;
    private Phase phase = Phase.IDLE;
    private int readyTick;
    private int attackWindowExpires;
    private int blockedTick = Integer.MIN_VALUE;
    private int releasedTick = Integer.MIN_VALUE;
    private int attackedTick = Integer.MIN_VALUE;
    private int tickId;
    private boolean pendingManualAttack;
    private boolean suppressingAttack;
    private InputConstants.Key pendingAttackKey;
    private int pendingAttackSlot = -1;

    private AutoBlock() {
        super("autoblock", "AutoBlock");
    }

    @Override
    public void registerEvents() {
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
                "AutoBlock.beforeAttack", EventPriority.HIGHEST, this::beforeAttack);
        EventBus.ATTACK_ENTITY_PRE.register(
                "AutoBlock.captureAttack",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    // Capture eligibility before damage so a killing attack can finish its
                    // animation.
                    this.pendingAnimationTarget =
                            event.attacker() == client.player
                                            && target(client) != null
                                            && event.target() instanceof LivingEntity
                                            && CombatReach.within(
                                                    client, event.target(), this.range.get())
                                    ? event.target()
                                    : null;
                });
        EventBus.ATTACK_ENTITY_POST.register(
                "AutoBlock.afterAttack",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    boolean completed = event.target() == this.pendingAnimationTarget;
                    this.pendingAnimationTarget = null;
                    if (completed && event.attacker() == client.player && canRun(client)) {

                        if (this.visual.get()) this.blockHit.start();
                        this.phase = Phase.REBLOCKING;
                        this.attackedTick = this.tickId;
                        this.readyTick = this.tickId + this.reblockTicks.get();
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
                            || this.use.owned() && !this.use.matches(event.client())) {
                        reset(event.client());
                    } else if (this.use.owned()
                            || this.phase != Phase.IDLE
                            || ClientReady.world(event.client())
                                    && this.releasedTick == this.tickId) {
                        // A held physical use key must not restart use inside our attack window.
                        event.cancel();
                    }
                });
        EventBus.PACKET_SEND_POST.register(
                "AutoBlock.actionBoundary",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!this.enabled.get()
                            || event.thread() != PacketThread.CLIENT
                            || !ClientReady.world(client)
                            || client.getConnection() == null
                            || event.connection() != client.getConnection().getConnection()) return;
                    // Include native/manual actions as well as requests issued by this module.
                    if (event.packet() instanceof ServerboundPlayerActionPacket action
                            && action.getAction()
                                    == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM)
                        this.releasedTick = this.tickId;
                    else if (event.packet() instanceof ServerboundAttackPacket)
                        this.attackedTick = this.tickId;
                });
    }

    private void tick(Minecraft client) {
        this.tickId++;
        if (!refresh(client)) return;
        if (this.pendingManualAttack
                && this.pendingAttackSlot != client.player.getInventory().getSelectedSlot())
            clearPendingAttack(client);
        if (this.use.owned() || this.phase != Phase.IDLE) captureAttackInput(client);
        if (!this.pendingManualAttack) return;
        if (!attackReady(client)) {
            this.suppressingAttack = true;
            CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_BLOCK);
            return;
        }
        InputConstants.Key key = this.pendingAttackKey;
        clearPendingAttack(client);
        // Re-submit only the input that was withheld. Vanilla chooses the current crosshair
        // target and dispatches it in handleKeybinds; no stored target or direct attack packet.
        if (key != null
                && key != InputConstants.UNKNOWN
                && key.equals(GameAccess.boundKey(client.options.keyAttack))) KeyMapping.click(key);
    }

    private void captureAttackInput(Minecraft client) {
        boolean clicked = false;
        while (client.options.keyAttack.consumeClick()) clicked = true;
        if (clicked) {
            this.pendingManualAttack = true;
            this.pendingAttackKey = GameAccess.boundKey(client.options.keyAttack);
            this.pendingAttackSlot = client.player.getInventory().getSelectedSlot();
        }
    }

    private void clearPendingAttack(Minecraft client) {
        this.pendingManualAttack = false;
        this.pendingAttackKey = null;
        this.pendingAttackSlot = -1;
        if (this.suppressingAttack) {
            this.suppressingAttack = false;
            CombatInputController.releaseAttack(client, CombatInputController.Owner.AUTO_BLOCK);
        }
    }

    private boolean canRun(Minecraft client) {
        return !CombatModuleCoordinator.automationSuspended()
                && this.enabled.get()
                && ClientReady.aliveGameplay(client)
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && (!this.requireRightClick.get()
                        || PhysicalInput.isGameplayKeyDown(client, client.options.keyUse))
                && !SilentAura.isActivationHeld(client);
    }

    private LivingEntity target(Minecraft client) {
        if (!canRun(client)) return null;
        LivingEntity closest = null;
        double distance = this.range.get() * this.range.get();
        for (var player : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, player)
                    || !client.player.hasLineOfSight(player)) continue;
            double candidate = EntityDistance.squaredToEntity(client, player);
            double angle =
                    ViewCone.angleBetween(
                            client.player.getViewVector(1),
                            player.getBoundingBox()
                                    .getCenter()
                                    .subtract(client.player.getEyePosition()));
            if (candidate <= distance && ViewCone.withinFov(angle, this.fov.get())) {
                closest = player;
                distance = candidate;
            }
        }
        return closest;
    }

    private boolean refresh(Minecraft client) {
        if (this.use.refresh(client)) {
            this.releasedTick = this.tickId;
            this.phase = Phase.IDLE;
            this.blockedTick = Integer.MIN_VALUE;
            clearPendingAttack(client);
        }
        if (!canRun(client)
                || client.player.isUsingItem() && !this.use.ownsNativeUse(client)
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

    private void beforeAttack(AttackInputEvent.Pre event) {
        Minecraft client = event.client();
        if (event.isCancelled()) return;
        boolean active = refresh(client);
        if (SilentAura.isActivationHeld(client)) return;
        if (!active && this.releasedTick != this.tickId) return;
        if (!attackReady(client)) {
            event.cancel();
            if (active && !CombatInputController.isInvokingTargetAttack()) {
                this.pendingManualAttack = true;
                this.pendingAttackKey = GameAccess.boundKey(client.options.keyAttack);
                this.pendingAttackSlot = client.player.getInventory().getSelectedSlot();
            }
        }
    }

    private boolean attackReady(Minecraft client) {
        int tick = this.tickId;
        if (tick == this.releasedTick || tick == this.blockedTick || tick == this.attackedTick)
            return false;
        if (this.phase == Phase.REBLOCKING && tick < this.readyTick) return false;
        if (this.use.owned()) {
            if (this.use.stop(client)) this.releasedTick = tick;
            this.phase = Phase.UNBLOCKING;
            this.readyTick = tick + this.unblockTicks.get();
            this.attackWindowExpires = this.readyTick + 2;
            return false;
        }
        return ClientReady.world(client)
                && !client.player.isUsingItem()
                && (this.phase != Phase.UNBLOCKING || tick >= this.readyTick);
    }

    private void reblock(Minecraft client) {
        if (!refresh(client)) return;
        int tick = this.tickId;
        if (this.pendingManualAttack || tick == this.releasedTick || tick == this.attackedTick)
            return;
        if (this.phase == Phase.UNBLOCKING && tick < this.attackWindowExpires) return;
        if (this.phase == Phase.REBLOCKING && tick < this.readyTick || this.use.owned()) return;
        if (!this.use.canStart(client)) return;
        // Resolve the current action/movement window, never the preceding sent rotation.
        RotationManager.Decision decision = RotationManager.resolve();
        Rotation rotation =
                decision != null
                        ? decision.rotation()
                        : new Rotation(client.player.getYRot(), client.player.getXRot());
        if (!RotationLease.holdManual(rotation)) return;
        if (this.use.start(client, rotation)) {
            this.phase = Phase.BLOCKING;
            this.blockedTick = tick;
        }
    }

    public boolean shouldRenderBlock(Minecraft client) {
        return this.enabled.get() && this.visual.get() && this.use.ownsNativeUse(client);
    }

    public boolean attackAnimationOnly() {
        return this.enabled.get();
    }

    public double animationProgress() {
        return this.blockHit.active() ? this.blockHit.progress() : 0.0;
    }

    public void reset(Minecraft client) {
        this.blockHit.reset();
        this.pendingAnimationTarget = null;
        resetBlocking(client);
    }

    public void suspend(Minecraft client) {
        discard(client);
    }

    private void resetBlocking(Minecraft client) {
        if (this.use.stop(client)) this.releasedTick = this.tickId;
        clearPendingAttack(client);
        this.phase = Phase.IDLE;
        this.blockedTick = Integer.MIN_VALUE;
        this.readyTick = this.attackWindowExpires = 0;
    }

    private void discard(Minecraft client) {
        this.use.discard();
        clearPendingAttack(client);
        this.blockHit.reset();
        this.pendingAnimationTarget = null;
        this.blockedTick = this.releasedTick = Integer.MIN_VALUE;
        this.attackedTick = Integer.MIN_VALUE;
        this.phase = Phase.IDLE;
        this.readyTick = this.attackWindowExpires = 0;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public String hudTag() {
        Minecraft client = Minecraft.getInstance();
        if (ClientReady.world(client) && !BlockingUse.supportsSwordBlock(client))
            return "Legacy (Experiment) / Unsupported";
        return "Legacy (Experiment) / " + (this.use.ownsNativeUse(client) ? "Blocking" : "Ready");
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        reset(client);
        this.enabled.set(value);
        ClientChat.send(client, "AutoBlock " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static ModuleModel.Setting[] settings() {
        return Descriptors.ALL.clone();
    }

    private static final class Descriptors {
        private static final ModuleModel.Setting[] ALL = {
            AutoBlock.INSTANCE.requireRightClick.describe(
                    "require_right_click",
                    "Require right click",
                    (client, value) -> {
                        AutoBlock.INSTANCE.reset(client);
                        AutoBlock.INSTANCE.requireRightClick.set(value);
                        return 1;
                    }),
            AutoBlock.INSTANCE.visual.describe(
                    "visual",
                    "Visual blocking",
                    (client, value) -> {
                        AutoBlock.INSTANCE.blockHit.reset();
                        AutoBlock.INSTANCE.visual.set(value);
                        return 1;
                    }),
            AutoBlock.INSTANCE.range.describe(
                    "range",
                    "Block range",
                    .1,
                    (client, value) -> {
                        AutoBlock.INSTANCE.reset(client);
                        AutoBlock.INSTANCE.range.set(value);
                        return 1;
                    }),
            AutoBlock.INSTANCE.fov.describe(
                    "fov",
                    "FOV",
                    1,
                    (client, value) -> {
                        AutoBlock.INSTANCE.reset(client);
                        AutoBlock.INSTANCE.fov.set(value);
                        return 1;
                    }),
            AutoBlock.INSTANCE.unblockTicks.describe(
                    "unblock_ticks",
                    "Wait before attack (ticks)",
                    1,
                    (client, value) -> {
                        AutoBlock.INSTANCE.reset(client);
                        AutoBlock.INSTANCE.unblockTicks.set(value);
                        return 1;
                    }),
            AutoBlock.INSTANCE.reblockTicks.describe(
                    "reblock_ticks",
                    "Reblock delay (ticks)",
                    1,
                    (client, value) -> {
                        AutoBlock.INSTANCE.reset(client);
                        AutoBlock.INSTANCE.reblockTicks.set(value);
                        return 1;
                    })
        };
    }

    private static BooleanSetting bool(String key, boolean value) {
        return new BooleanSetting.Builder().name(key).defaultValue(value).build();
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return AutoBlock.settings();
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withHudTag("Legacy");
    }
}
