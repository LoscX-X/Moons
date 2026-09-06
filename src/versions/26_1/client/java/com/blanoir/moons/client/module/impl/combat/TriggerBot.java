package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.management.input.CombatInputController;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;
import java.util.Set;

public final class TriggerBot {
    private static final double DEFAULT_MAX_CHARGE = 1.0D;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;

    private static final StringSetting TARGET_ENTITIES =
            new StringSetting.Builder()
                    .name("triggerbot.target.entities")
                    .defaultValue("")
                    .build();

    private static double nextAttackCharge = DEFAULT_MAX_CHARGE;
    private static int fullChargeTicks = 0;
    private static int sampledChargeTick = Integer.MIN_VALUE;
    private static double sampledAttackCharge;
    private static boolean missedCrosshair = true;
    private static long missDelayDeadlineNanos = 0L;
    private static boolean silentAuraInputOwned;
    private static String silentAuraGate = "idle";
    private static final Set<Identifier> targetEntityTypes =
            Targeting.parseEntityTypeIds(TARGET_ENTITIES.get());

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("triggerbot.enabled")
                    .defaultValue(false)
                    .build();

    private static final DoubleSetting MIN_CHARGE =
            new DoubleSetting.Builder()
                    .name("triggerbot.minCharge")
                    .defaultValue(0.7D)
                    .range(0.7D, 1.3D)
                    .build();

    private static final DoubleSetting MAX_CHARGE =
            new DoubleSetting.Builder()
                    .name("triggerbot.maxCharge")
                    .defaultValue(1.0D)
                    .range(0.7D, 1.3D)
                    .build();

    private static final DoubleSetting MIN_MISS_DELAY =
            new DoubleSetting.Builder()
                    .name("triggerbot.minMissDelaySeconds")
                    .defaultValue(0.0D)
                    .range(0.0D, 2.0D)
                    .build();

    private static final DoubleSetting MAX_MISS_DELAY =
            new DoubleSetting.Builder()
                    .name("triggerbot.maxMissDelaySeconds")
                    .defaultValue(0.0D)
                    .range(0.0D, 2.0D)
                    .build();

    private static final BooleanSetting THROUGH_BLOCK_ENABLED =
            new BooleanSetting.Builder()
                    .name("triggerbot.throughBlock.enabled")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting TARGET_PLAYERS =
            new BooleanSetting.Builder()
                    .name("triggerbot.target.player")
                    .defaultValue(true)
                    .build();

    private static final BooleanSetting TARGET_MOBS =
            new BooleanSetting.Builder()
                    .name("triggerbot.target.mob")
                    .defaultValue(false)
                    .build();

    private TriggerBot() {
    }

    private enum RayEntry {
        CAMERA_RAY,
        SILENT_RAY
    }

    /** Result of the shared ray -> cooldown -> Critical -> vanilla-click pipeline. */
    private record AutomaticAttackResult(boolean attacked, String gate) {
    }

    public static void init() {
        EventBus.TICK.register("TriggerBot.tick", event -> prepareSilentAuraInput(event.client()));
        EventBus.PLAYER_UPDATE.register("TriggerBot.playerUpdate", event -> playerUpdate(event.client()));
    }

    /** Uses this tick's pick result and submits before LocalPlayer movement. */
    private static void playerUpdate(Minecraft client) {
        if (client == null || client.player == null || client.level == null
                || client.gameMode == null || MinecraftClientAccess.screen(client) != null) {
            return;
        }
        if (SilentAuraRuntime.activationHeld(client)) {
            playerUpdateSilentRay(client);
            return;
        }
        if (!ENABLED.get()) return;
        playerUpdateCameraRay(client);
    }

    /** Ordinary TriggerBot entry: the vanilla camera pick result owns the ray. */
    private static void playerUpdateCameraRay(Minecraft client) {
        if (!Targeting.isHoldingTriggerWeapon(client)) {
            rejectCameraRay(client);
            return;
        }

        // Cooldown/overcharge is a property of the held weapon, not of target
        // visibility. Advancing it only after acquiring a ray made a fully
        // charged player wait again after jumping into melee range.
        attackCharge(client);

        Entity target = getAttackableCrosshairTarget(client);
        if (target == null || isWithinSafeAttackRange(client, target)) {
            rejectCameraRay(client);
            return;
        }

        if (isWaitingForMissDelay()) {
            Critical.cancelAutomaticPrediction(client);
            return;
        }

        if (isPlayerPhysicallyAttacking(client)) {
            Critical.cancelAutomaticPrediction(client);
            return;
        }

        // Critical owns an already queued attack. Asking it again used
        // to return DEFERRED every tick and reset TriggerBot's charge/threshold
        // state repeatedly, which made Silent attacks occur only occasionally.
        if (Critical.isAimingWindowActive()) {
            return;
        }

        AutomaticAttackResult result = attackRayTarget(
                client, target, RayEntry.CAMERA_RAY);
        if (result.attacked()) resetAttackState();
    }

    /**
     * SilentAura input ownership lives here rather than in the aimer. The
     * physical button remains readable through GLFW while its vanilla mapping
     * is suppressed, so Aura can keep tracking without leaking a camera click.
     */
    private static void prepareSilentAuraInput(Minecraft client) {
        boolean active = SilentAuraRuntime.activationHeld(client);
        if (!active) {
            if (!silentAuraInputOwned) return;
            silentAuraInputOwned = false;
            CombatInputController.releaseAttack(
                    client, CombatInputController.Owner.SILENT_AURA);
            Critical.cancelAutomaticPrediction(client);
            resetPreparedAttackState(MIN_CHARGE.get(), MAX_CHARGE.get());
            silentAuraGate = "idle";
            return;
        }

        if (!silentAuraInputOwned) {
            silentAuraInputOwned = true;
            Critical.cancelAutomaticPrediction(client);
            resetPreparedAttackState(
                    SilentAuraConfig.minCharge(), SilentAuraConfig.maxCharge());
        }
        while (client.options.keyAttack.consumeClick()) {
            // TriggerBot dispatches the eventual click after proving silent-ray.
        }
        CombatInputController.suppressAttack(
                client, CombatInputController.Owner.SILENT_AURA);
        attackCharge(client);
    }

    /** Silent TriggerBot entry: use the rotation this tick will publish. */
    private static void playerUpdateSilentRay(Minecraft client) {
        LivingEntity target = getSilentRayTarget(client);
        if (target == null) return;

        AutomaticAttackResult result = attackRayTarget(
                client, target, RayEntry.SILENT_RAY);
        silentAuraGate = result.gate();
        if (!result.attacked()) return;

        resetPreparedAttackState(
                SilentAuraConfig.minCharge(), SilentAuraConfig.maxCharge());
        Animations.onAttack();
        SilentAuraRuntime.onSuccessfulAttack(client, target);
    }

    private static LivingEntity getSilentRayTarget(Minecraft client) {
        LivingEntity intended = SilentAuraRuntime.currentTarget(client);
        if (!isConfiguredSilentTarget(client, intended)) {
            rejectSilentRay(client, "no target");
            return null;
        }

        SilentAuraRuntime.AttackRotation rotation = SilentAuraRuntime.attackRotation(client);
        if (!rotation.valid() || rotation.targetId() != intended.getId()) {
            rejectSilentRay(client, "waiting rotation");
            return null;
        }

        double range = CombatReach.entityInteractionRange(
                client, SilentAuraConfig.aimRange());
        if (range <= 0.0D) {
            rejectSilentRay(client, "range");
            return null;
        }

        Entity intercepted = Targeting.findTargetOnRay(
                client,
                rotation.eye(),
                rotation.look(),
                range,
                entity -> entity instanceof LivingEntity living
                        && living != client.player
                        && living.isAlive()
                        && living.isAttackable()
                        && !living.isSpectator(),
                false);
        LivingEntity target = intercepted instanceof LivingEntity living
                ? living : intended;
        if (!isConfiguredSilentTarget(client, target)) {
            rejectSilentRay(client, "ray blocked");
            return null;
        }
        if (client.level.getEntity(target.getId()) != target) {
            rejectSilentRay(client, "target moved");
            return null;
        }

        RaytraceUtils.EntityRayState ray = RaytraceUtils.traceEntity(
                client, rotation.eye(), rotation.look(), range, target);
        if (ray != RaytraceUtils.EntityRayState.HIT) {
            rejectSilentRay(client, ray.name().toLowerCase(Locale.ROOT));
            return null;
        }
        return target;
    }

    private static boolean isConfiguredSilentTarget(
            Minecraft client,
            Entity target
    ) {
        return Targeting.isConfiguredTarget(
                client,
                target,
                SilentAuraConfig.targetPlayers(),
                SilentAuraConfig.targetMobs(),
                SilentAuraConfig.targetEntityTypes());
    }

    private static void rejectCameraRay(Minecraft client) {
        markMissedCrosshair();
        Critical.cancelAutomaticPrediction(client);
    }

    private static void rejectSilentRay(Minecraft client, String reason) {
        // A one-frame packet-ray AIM is not target invalidation. Preserve the
        // old Predict reservation so cooldown/jump alignment survives the
        // aimer's recovery; the null return still makes attacking impossible
        // until this same target produces a real HIT again.
        if (!"aim".equals(reason)) {
            Critical.cancelAutomaticPrediction(client);
        }
        silentAuraGate = reason;
    }

    private static void resetAttackState() {
        fullChargeTicks = 0;
        sampledChargeTick = Integer.MIN_VALUE;
        sampledAttackCharge = 0.0D;
        missedCrosshair = false;
        missDelayDeadlineNanos = 0L;
        nextAttackCharge = randomChargeThreshold();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    private static int showThroughBlockStatus(Minecraft client) {
        ClientChat.send(client, "TriggerBot Through Block: "
                + (THROUGH_BLOCK_ENABLED.get() ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setThroughBlockEnabled(Minecraft client, boolean value) {
        THROUGH_BLOCK_ENABLED.set(value);
        markMissedCrosshair();
        return showThroughBlockStatus(client);
    }

    private static void markMissedCrosshair() {
        missedCrosshair = true;
        missDelayDeadlineNanos = 0L;
    }

    private static boolean isWaitingForMissDelay() {
        if (!missedCrosshair) {
            return false;
        }

        if (missDelayDeadlineNanos == 0L) {
            missDelayDeadlineNanos = System.nanoTime() + missDelayNanos();
        }

        if (System.nanoTime() < missDelayDeadlineNanos) {
            return true;
        }

        missedCrosshair = false;
        missDelayDeadlineNanos = 0L;
        return false;
    }

    private static long missDelayNanos() {
        double delaySeconds = randomMissDelaySeconds();
        return (long) (delaySeconds * NANOS_PER_SECOND);
    }

    private static double randomMissDelaySeconds() {
        return RandomMath.between(MIN_MISS_DELAY.get(), MAX_MISS_DELAY.get());
    }

    private static double attackCharge(Minecraft client) {
        int tick = client.player.tickCount;
        if (sampledChargeTick == tick) {
            return sampledAttackCharge;
        }
        float cooldownProgress = client.player.getAttackStrengthScale(0.0F);
        if (cooldownProgress < 1.0F) {
            fullChargeTicks = 0;
            sampledAttackCharge = cooldownProgress;
        } else {
            fullChargeTicks++;
            sampledAttackCharge = cooldownProgress
                    + fullChargeTicks / client.player.getCurrentItemAttackStrengthDelay();
        }
        sampledChargeTick = tick;
        return sampledAttackCharge;
    }

    /**
     * Both ray entries converge here. Neither aimer owns cooldown, Critical
     * state or dispatch; they only supply a target proven by their own ray.
     */
    private static AutomaticAttackResult attackRayTarget(
            Minecraft client,
            Entity target,
            RayEntry entry
    ) {
        if (client == null || client.player == null || client.level == null
                || client.gameMode == null || target == null || !target.isAlive()) {
            return new AutomaticAttackResult(false, "invalid");
        }
        if (!Targeting.isHoldingTriggerWeapon(client)) {
            return new AutomaticAttackResult(false, "weapon");
        }

        double charge = attackCharge(client);
        boolean useCritical = entry != RayEntry.SILENT_RAY
                || SilentAuraConfig.criticalIntegration();
        boolean criticalAttack = false;
        if (useCritical) {
            // Preserve the original TriggerBot + Predict contract: Critical
            // sees how many ticks remain before the sampled charge threshold,
            // so it can align that cooldown with the current jump instead of
            // starting its forecast only after the threshold has elapsed.
            int ticksUntilReady = ticksUntilChargeThreshold(
                    client, charge, nextAttackCharge);
            Critical.AutomaticAttackGate criticalGate =
                    Critical.gateAutomaticAttack(client, target, ticksUntilReady);
            if (criticalGate != Critical.AutomaticAttackGate.ALLOW
                    && criticalGate != Critical.AutomaticAttackGate.ATTACK) {
                return new AutomaticAttackResult(false,
                        "critical " + criticalGate.name().toLowerCase(Locale.ROOT));
            }
            criticalAttack = criticalGate == Critical.AutomaticAttackGate.ATTACK;
        }

        // ATTACK is the current-structure equivalent of old Predict ATTACKED:
        // the reservation already counted down its cooldown lead, so sampling
        // TriggerBot's threshold a second time here would weaken old behavior.
        if (!criticalAttack && charge + 1.0E-4D < nextAttackCharge) {
            return new AutomaticAttackResult(false, String.format(
                    Locale.ROOT, "charge %.2f/%.2f", charge, nextAttackCharge));
        }

        boolean cameraOwnsTarget = client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() == target;
        boolean forceTargetOverride = entry == RayEntry.SILENT_RAY
                || !cameraOwnsTarget;
        boolean attacked = useCritical
                ? CombatInputController.attackTargetNow(
                client, target, forceTargetOverride)
                : Critical.withoutSilentAuraCritical(() ->
                CombatInputController.attackTargetNow(
                        client, target, forceTargetOverride));
        if (!attacked) {
            return new AutomaticAttackResult(false, "attack dispatch");
        }

        Critical.cancelAutomaticPrediction(client);
        return new AutomaticAttackResult(true, "attack");
    }

    private static void resetPreparedAttackState(
            double minimumCharge,
            double maximumCharge
    ) {
        fullChargeTicks = 0;
        sampledChargeTick = Integer.MIN_VALUE;
        sampledAttackCharge = 0.0D;
        nextAttackCharge = randomChargeThreshold(minimumCharge, maximumCharge);
    }

    private static boolean isPlayerPhysicallyAttacking(Minecraft client) {
        return CombatInputController.isPhysicallyDown(client, client.options.keyAttack);
    }

    private static int ticksUntilChargeThreshold(
            Minecraft client,
            double currentCharge,
            double requiredCharge
    ) {
        if (currentCharge >= requiredCharge) return 0;
        double attackDelay = Math.max(1.0D,
                client.player.getCurrentItemAttackStrengthDelay());
        return Math.max(1,
                (int) Math.ceil((requiredCharge - currentCharge) * attackDelay));
    }

    private static Entity getAttackableCrosshairTarget(Minecraft client) {
        HitResult hitResult = client.hitResult;
        if (hitResult instanceof EntityHitResult entityHitResult
                && Targeting.isConfiguredTarget(
                        client,
                        entityHitResult.getEntity(),
                        TARGET_PLAYERS.get(),
                        TARGET_MOBS.get(),
                        targetEntityTypes)) {
            return entityHitResult.getEntity();
        }
        return THROUGH_BLOCK_ENABLED.get()
                ? Targeting.findConfiguredTargetOnViewRay(
                        client,
                        TARGET_PLAYERS.get(),
                        TARGET_MOBS.get(),
                        targetEntityTypes,
                        true)
                : null;
    }

    public static boolean isWithinSafeAttackRange(Minecraft client, Entity target) {
        double safeRange = safeInteractionRange(client);
        return !(EntityDistance.squaredToEntity(client, target) <= safeRange * safeRange);
    }

    public static boolean isAttackRayReady(Minecraft client, Entity target) {
        return client == null || target == null;
    }

    public static String silentAuraGate() {
        return silentAuraGate;
    }

    /** Vanilla weapon cooldown shown by SilentAura's compact HUD suffix. */
    public static int attackChargePercent(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null) return 0;
        double charge = currentPlayer.getAttackStrengthScale(0.0F);
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, charge)) * 100.0D);
    }

    private static double safeInteractionRange(Minecraft client) {
        return Math.max(0.0D,
                client.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        if (newEnabled) {
            CombatModuleCoordinator.beforeEnable(
                    client, CombatModuleCoordinator.Role.TRIGGER_BOT);
        }
        ENABLED.set(newEnabled);
        fullChargeTicks = 0;
        sampledChargeTick = Integer.MIN_VALUE;
        sampledAttackCharge = 0.0D;
        missedCrosshair = true;
        missDelayDeadlineNanos = 0L;
        nextAttackCharge = randomChargeThreshold();
        ClientChat.send(client, "TriggerBot " + statusText()
                + ". Charge range: " + formatChargeRange()
                + ", miss delay: " + formatMissDelayRange()
                + "s, through block: " + (THROUGH_BLOCK_ENABLED.get() ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setChargeRange(Minecraft client, String rawRange) {
        ChargeRange parsedRange = parseChargeRange(rawRange);
        if (parsedRange == null) {
            ClientChat.send(client, "Invalid TriggerBot range. Use .moons triggerbot x-x, where each x is between 0.7 and 1.3, for example .moons triggerbot 0.7-1.3.");
            return 0;
        }
        MIN_CHARGE.set(parsedRange.min());
        MAX_CHARGE.set(parsedRange.max());
        sampledChargeTick = Integer.MIN_VALUE;
        nextAttackCharge = randomChargeThreshold();
        ClientChat.send(client, "TriggerBot charge range set to " + formatChargeRange() + ".");
        return 1;
    }

    public static int setMissDelayRange(Minecraft client, String rawRange) {
        ChargeRange parsedRange = parseRange(rawRange, MIN_MISS_DELAY.getMin(), MAX_MISS_DELAY.getMax());
        if (parsedRange == null) {
            ClientChat.send(client, "Invalid TriggerBot miss delay. Use .moons triggerbot miss x-x, where each x is seconds between 0 and 2, for example .moons triggerbot miss 0.05-0.2.");
            return 0;
        }
        MIN_MISS_DELAY.set(parsedRange.min());
        MAX_MISS_DELAY.set(parsedRange.max());
        missedCrosshair = true;
        missDelayDeadlineNanos = 0L;
        ClientChat.send(client, "TriggerBot miss delay range set to " + formatMissDelayRange() + "s.");
        return 1;
    }

    private static int showTargetStatus(Minecraft client) {
        ClientChat.send(client, "TriggerBot targets: " + Targeting.configuredTargetStatus(
                        TARGET_PLAYERS.get(), TARGET_MOBS.get(), targetEntityTypes)
                + ". Usage: .moons triggerbot target <add|remove> <player|mob|all|entity id>.");
        return 1;
    }

    public static int setTargetCategory(Minecraft client, String category, boolean add) {
        if ("player".equals(category) || "all".equals(category)) TARGET_PLAYERS.set(add);
        if ("mob".equals(category) || "all".equals(category)) TARGET_MOBS.set(add);
        if ("all".equals(category) && !add) {
            targetEntityTypes.clear();
            TARGET_ENTITIES.set(Targeting.serializeEntityTypeIds(targetEntityTypes));
        }
        markMissedCrosshair();
        return showTargetStatus(client);
    }

    private static ChargeRange parseChargeRange(String rawRange) {
        return parseRange(rawRange, MIN_CHARGE.getMin(), MAX_CHARGE.getMax());
    }

    private static ChargeRange parseRange(String rawRange, double minSupported, double maxSupported) {
        String normalizedRange = rawRange.trim().toLowerCase(Locale.ROOT);
        String[] rangeParts = normalizedRange.split("-", -1);
        if (rangeParts.length != 1 && rangeParts.length != 2) {
            return null;
        }

        try {
            double parsedMin = Double.parseDouble(rangeParts[0]);
            double parsedMax = rangeParts.length == 1 ? parsedMin : Double.parseDouble(rangeParts[1]);

            if (parsedMin > parsedMax) {
                double swap = parsedMin;
                parsedMin = parsedMax;
                parsedMax = swap;
            }

            if (!Double.isFinite(parsedMin) || !Double.isFinite(parsedMax) || parsedMin < minSupported || parsedMax > maxSupported) {
                return null;
            }

            return new ChargeRange(parsedMin, parsedMax);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double randomChargeThreshold() {
        return RandomMath.between(MIN_CHARGE.get(), MAX_CHARGE.get());
    }

    private static double randomChargeThreshold(double minimum, double maximum) {
        return RandomMath.between(Math.min(minimum, maximum), Math.max(minimum, maximum));
    }

    private static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    private static String formatChargeRange() {
        return formatDouble(MIN_CHARGE.get()) + "-" + formatDouble(MAX_CHARGE.get());
    }

    private static String formatMissDelayRange() {
        return formatDouble(MIN_MISS_DELAY.get()) + "-" + formatDouble(MAX_MISS_DELAY.get());
    }

    private static String formatDouble(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }

    private record ChargeRange(double min, double max) {
    }
}
