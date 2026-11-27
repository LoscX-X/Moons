package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.network.PacketSendEvent;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.PiercingWeapon;

/** Allows manual spear jabs while maintaining the main-hand charge attack. */
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Allows manual spear jabs while you hold a charged spear attack.")
public final class AutoSpear extends ClientFeature {
    public static final AutoSpear INSTANCE = new AutoSpear();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("autospear.enabled").defaultValue(false).build();
    private final BooleanSetting ignoreCooldown =
            new BooleanSetting.Builder()
                    .name("autospear.ignoreCooldown")
                    .defaultValue(false)
                    .build();
    private final BooleanSetting fullHold =
            new BooleanSetting.Builder().name("autospear.fullHold").defaultValue(false).build();
    private final DoubleSetting motionMultiply =
            new DoubleSetting.Builder()
                    .name("autospear.motionMultiply")
                    .defaultValue(1.0)
                    .range(1.0, 5.0)
                    .build();
    private final BooleanSetting impactBurst =
            new BooleanSetting.Builder().name("autospear.impactBurst").defaultValue(false).build();
    private final DoubleSetting impactMultiply =
            new DoubleSetting.Builder()
                    .name("autospear.impactMultiply")
                    .defaultValue(8.0)
                    .range(1.0, 20.0)
                    .build();
    // Retain the original Blink keys so existing presets carry over to FakeLag.
    private final BooleanSetting fakeLagEnabledSetting =
            new BooleanSetting.Builder().name("autospear.blink").defaultValue(false).build();
    private final IntSetting fakeLagDelay =
            new IntSetting.Builder()
                    .name("autospear.blinkDurationMs")
                    .defaultValue(150)
                    .range(50, 500)
                    .build();
    private final IntSetting fakeLagDuration =
            new IntSetting.Builder()
                    .name("autospear.fakeLagDurationMs")
                    .defaultValue(500)
                    .range(100, 2000)
                    .build();

    private LocalPlayer heldPlayer;
    private ClientLevel heldLevel;
    private ItemStack heldStack;
    private int heldSlot = -1;
    private boolean restoreUse;
    private boolean releasing;

    private final AutoSpearFakeLag fakeLag = sub(new AutoSpearFakeLag(this));
    private final AutoSpearMotion motion = sub(new AutoSpearMotion(this));
    private final AutoSpearImpact impact = sub(new AutoSpearImpact(this));

    boolean impactActive() {
        return impact.active();
    }

    boolean armImpact(Minecraft client) {
        return impact.arm(client);
    }

    void stopImpact(Minecraft client) {
        impact.stop(client);
    }

    void startPacketDelay(Minecraft client) {
        fakeLag.start(client);
    }

    void flushDelayedPackets(Minecraft client) {
        fakeLag.flush(client);
    }

    private AutoSpear() {
        super("autospear", "SpearAssist");
    }

    @Override
    public void registerEvents() {
        super.registerEvents();
        EventBus.TICK.register("AutoSpear.tick", event -> tick(event.client()));
        EventBus.TICK_END.register("AutoSpear.hold", event -> refreshHold(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "AutoSpear.context", event -> discardHold(event.client()));
    }

    public void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "AutoSpear.fullHold", EventPriority.HIGHEST, this::beforeSend);
        fakeLag.initPacketListeners();
    }

    private void tick(Minecraft client) {
        refreshHold(client);
        if (!this.enabled.get()
                || !ClientReady.aliveGameplay(client)
                || !client.isWindowActive()
                || client.isPaused()
                || client.player.isSpectator()
                || !client.player.isUsingItem()
                || client.player.getUsedItemHand() != InteractionHand.MAIN_HAND
                || !client.options.keyUse.isDown()) return;

        ItemStack stack = client.player.getMainHandItem();
        if (stack.getUseAnimation() != ItemUseAnimation.SPEAR) return;
        PiercingWeapon weapon = stack.get(DataComponents.PIERCING_WEAPON);
        if (weapon == null || !stack.isItemEnabled(client.level.enabledFeatures())) return;

        // Vanilla discards attack clicks while using an item. Consume them here
        // before handleKeybinds, including quick presses released between ticks.
        // A held button never repeats; blocked clicks are never queued for later.
        boolean clicked = false;
        while (client.options.keyAttack.consumeClick()) {
            clicked = true;
        }
        if (!clicked) return;
        // Only bypass the local gate. The server still validates the STAB cooldown.
        if (!this.ignoreCooldown.get() && client.player.cannotAttackWithItem(stack, 0)) return;

        // This sends STAB and updates the local attack timer. Ordinary entity
        // attack packets are rejected for piercing weapons. Keep use active;
        // the server applies Lunge through the post-piercing enchantment effects.
        MinecraftClientAccess.piercingAttack(client, weapon);
    }

    private static boolean usingSpear(Minecraft client) {
        return ClientReady.interaction(client)
                && client.player.isUsingItem()
                && client.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && client.player.getUseItem() == client.player.getMainHandItem()
                && client.player.getUseItem().getUseAnimation() == ItemUseAnimation.SPEAR
                && client.player.getUseItem().has(DataComponents.PIERCING_WEAPON);
    }

    private boolean sameHold(Minecraft client) {
        return this.heldPlayer != null
                && ClientReady.interaction(client)
                && client.player == this.heldPlayer
                && client.level == this.heldLevel
                && client.player.getInventory().getSelectedSlot() == this.heldSlot
                && client.player.getMainHandItem() == this.heldStack
                && !this.heldStack.isEmpty();
    }

    private void rememberHold(Minecraft client) {
        this.heldPlayer = client.player;
        this.heldLevel = client.level;
        this.heldStack = client.player.getMainHandItem();
        this.heldSlot = client.player.getInventory().getSelectedSlot();
    }

    private void refreshHold(Minecraft client) {
        if (!this.enabled.get()
                || !this.fullHold.get()
                || !ClientReady.interaction(client)
                || client.player.isDeadOrDying()
                || client.player.isSpectator()) {
            discardHold(client);
            return;
        }
        if (this.heldPlayer != null && !sameHold(client)) discardHold(client);
        if (this.heldPlayer == null && usingSpear(client)) rememberHold(client);
        if (!sameHold(client)) return;

        // Cancelling the packet does not cancel vanilla's local releaseUsingItem.
        // Restore only that suppressed release, never a server-forced interruption.
        if (this.restoreUse && !client.player.isUsingItem()) {
            client.player.startUsingItem(InteractionHand.MAIN_HAND);
        }
        this.restoreUse = false;
        if (!usingSpear(client)) {
            discardHold(client);
            return;
        }
        CombatInputController.holdUse(client, CombatInputController.Owner.AUTO_SPEAR);
    }

    private void beforeSend(PacketSendEvent.Pre event) {
        if (this.releasing
                || !this.enabled.get()
                || !this.fullHold.get()
                || !(event.packet() instanceof ServerboundPlayerActionPacket packet)
                || packet.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM)
            return;
        Minecraft client = Minecraft.getInstance();
        if (!usingSpear(client) || client.player.isDeadOrDying() || client.player.isSpectator())
            return;
        rememberHold(client);
        this.restoreUse = true;
        event.cancel();
    }

    private void discardHold(Minecraft client) {
        this.heldPlayer = null;
        this.heldLevel = null;
        this.heldStack = null;
        this.heldSlot = -1;
        this.restoreUse = false;
        CombatInputController.releaseUse(client, CombatInputController.Owner.AUTO_SPEAR);
    }

    /** Core suspension abandons delayed packets and held input without replaying or releasing use. */
    public void suspend(Minecraft client) {
        fakeLag.discard();
        motion.reset();
        impact.stop(client);
        discardHold(client);
    }

    /** Releases our native use when the option/module is disabled or unloaded. */
    public void reset(Minecraft client) {
        fakeLag.flush(client);
        motion.reset();
        impact.stop(client);
        boolean release = sameHold(client) && (!client.player.isUsingItem() || usingSpear(client));
        this.releasing = true;
        try {
            if (release) client.gameMode.releaseUsingItem(client.player);
        } finally {
            discardHold(client);
            this.releasing = false;
        }
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    double motionMultiply() {
        return this.motionMultiply.get();
    }

    public boolean impactBurstEnabled() {
        return this.impactBurst.get();
    }

    double impactMultiply() {
        return this.impactMultiply.get();
    }

    public int setImpactBurst(Minecraft client, boolean value) {
        impact.stop(client);
        motion.reset();
        this.impactBurst.set(value);
        return 1;
    }

    public int setImpactMultiply(Minecraft client, double value) {
        impact.stop(client);
        this.impactMultiply.set(value);
        return 1;
    }

    public boolean fakeLagEnabled() {
        return this.fakeLagEnabledSetting.get();
    }

    int fakeLagDelayMs() {
        return this.fakeLagDelay.get();
    }

    int fakeLagDurationMs() {
        return this.fakeLagDuration.get();
    }

    public int setFakeLag(Minecraft client, boolean value) {
        this.fakeLagEnabledSetting.set(value);
        fakeLag.flush(client);
        motion.reset();
        return 1;
    }

    public int setFakeLagDelay(Minecraft client, int value) {
        this.fakeLagDelay.set(value);
        fakeLag.flush(client);
        return 1;
    }

    public int setFakeLagDuration(Minecraft client, int value) {
        this.fakeLagDuration.set(value);
        fakeLag.flush(client);
        return 1;
    }

    public int setMotionMultiply(Minecraft ignoredClient, double value) {
        this.motionMultiply.set(value);
        motion.reset();
        return 1;
    }

    public int setIgnoreCooldown(Minecraft ignoredClient, boolean value) {
        this.ignoreCooldown.set(value);
        return 1;
    }

    public int setFullHold(Minecraft client, boolean value) {
        this.fullHold.set(value);
        if (!value) reset(client);
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        if (!value) reset(client);
        ClientChat.send(client, "SpearAssist " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            ignoreCooldown.describeStored(
                    "ignore_cooldown", "Ignore client cooldown", this::setIgnoreCooldown),
            fullHold.describeStored("full_hold", "Full hold", this::setFullHold),
            motionMultiply.describeStored(
                    "motion_multiply", "Motion multiply", .1, this::setMotionMultiply),
            impactBurst.describeStored(
                    "impact_burst", "Impact burst (experimental)", this::setImpactBurst),
            impactMultiply
                    .describeStored(
                            "impact_multiply", "Impact multiply", .5, this::setImpactMultiply)
                    .visibleWhen(this::impactBurstEnabled),
            fakeLagEnabledSetting.describeStored("blink", "FakeLag", this::setFakeLag),
            fakeLagDelay
                    .describeStored(
                            "blink_duration", "FakeLag delay (ms)", 25, this::setFakeLagDelay)
                    .visibleWhen(this::fakeLagEnabled),
            fakeLagDuration
                    .describeStored(
                            "fakelag_duration",
                            "FakeLag duration (ms)",
                            50,
                            this::setFakeLagDuration)
                    .visibleWhen(this::fakeLagEnabled)
        };
    }
}
