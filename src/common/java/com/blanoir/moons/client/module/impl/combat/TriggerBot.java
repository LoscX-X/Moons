package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.manager.targeting.Targeting;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.registry.RegistryLists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import java.util.Locale;
import java.util.Set;

public final class TriggerBot {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;

    private static final StringSetting TARGET_ENTITIES =
            new StringSetting.Builder().name("triggerbot.target.entities").defaultValue("").build();

    private static boolean missedCrosshair = true;
    private static long missDelayDeadlineNanos = 0L;
    private static final Set<ResourceLocation> targetEntityTypes =
            Targeting.parseEntityTypeIds(TARGET_ENTITIES.get());

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("triggerbot.enabled").defaultValue(false).build();

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
                    .name("triggerbot.target.thePlayer")
                    .defaultValue(true)
                    .build();

    private TriggerBot() {}

    /** Result of the camera ray -> Critical -> vanilla-click pipeline. */
    private record AutomaticAttackResult(boolean attacked, String gate) {}

    public static void init() {
        EventBus.PLAYER_UPDATE.register(
                "TriggerBot.playerUpdate", event -> playerUpdate(event.client()));
    }

    /** Samples the current view ray and submits before EntityPlayerSP movement. */
    private static void playerUpdate(Minecraft client) {
        if (client == null
                || client.thePlayer == null
                || client.theWorld == null
                || client.playerController == null
                || MinecraftClientAccess.screen(client) != null) {
            return;
        }
        if (SilentAuraRuntime.activationHeld(client)) {
            return;
        }
        if (!ENABLED.get()) return;
        playerUpdateCameraRay(client);
    }

    /** Ordinary TriggerBot entry: use the player's current view within vanilla reach. */
    private static void playerUpdateCameraRay(Minecraft client) {
        if (!Targeting.isHoldingTriggerWeapon(client)) {
            rejectCameraRay(client);
            return;
        }

        Entity target = getAttackableCrosshairTarget(client);
        if (target == null || CombatGeometry.outsideVanillaRange(client, target)) {
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

        AutomaticAttackResult result = attackRayTarget(client, target);
        if (result.attacked()) resetAttackState();
    }

    private static void rejectCameraRay(Minecraft client) {
        markMissedCrosshair();
        Critical.cancelAutomaticPrediction(client);
    }

    private static void resetAttackState() {
        missedCrosshair = false;
        missDelayDeadlineNanos = 0L;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    private static int showThroughBlockStatus(Minecraft client) {
        ClientChat.send(
                client,
                "TriggerBot Through Block: "
                        + (THROUGH_BLOCK_ENABLED.get() ? "enabled" : "disabled")
                        + ".");
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

    /**
     * The camera-ray entry owns Critical
     * state and dispatch after validating the current view ray.
     */
    private static AutomaticAttackResult attackRayTarget(Minecraft client, Entity target) {
        if (client == null
                || client.thePlayer == null
                || client.theWorld == null
                || client.playerController == null
                || target == null
                || !target.isEntityAlive()) {
            return new AutomaticAttackResult(false, "invalid");
        }
        if (!Targeting.isHoldingTriggerWeapon(client)) {
            return new AutomaticAttackResult(false, "weapon");
        }

        // 1.8.9 has no weapon charge. Preserve Critical's airborne timing gate.
        Critical.AutomaticAttackGate criticalGate = Critical.gateAutomaticAttack(client, target, 0);
        if (criticalGate != Critical.AutomaticAttackGate.ALLOW
                && criticalGate != Critical.AutomaticAttackGate.ATTACK) {
            return new AutomaticAttackResult(
                    false, "critical " + criticalGate.name().toLowerCase(Locale.ROOT));
        }

        var hit =
                CombatGeometry.attackHit(
                        client,
                        target,
                        client.thePlayer.getPositionEyes(1.0F),
                        client.thePlayer.getLook(1.0F),
                        safeInteractionRange(client));
        if (hit == null) return new AutomaticAttackResult(false, "target moved");
        boolean attacked =
                HitSelect.withoutFiltering(
                        () -> CombatInputController.attackTargetNow(client, hit, true));
        if (!attacked) {
            return new AutomaticAttackResult(false, "attack dispatch");
        }

        Critical.cancelAutomaticPrediction(client);
        return new AutomaticAttackResult(true, "attack");
    }

    private static boolean isPlayerPhysicallyAttacking(Minecraft client) {
        return com.blanoir.moons.client.input.PhysicalInput.isGameplayKeyDown(
                client, client.gameSettings.keyBindAttack);
    }

    private static Entity getAttackableCrosshairTarget(Minecraft client) {
        if (client == null || client.thePlayer == null || client.theWorld == null) return null;
        var eye = client.thePlayer.getPositionEyes(1.0F);
        var look = client.thePlayer.getLook(1.0F);
        double range = safeInteractionRange(client);
        boolean throughBlocks = THROUGH_BLOCK_ENABLED.get();
        // The shared camera hit can be stale or extended by Reach. Pick afresh,
        // retaining non-target entities as occluders instead of looking through them.
        Entity target =
                CombatGeometry.findTargetOnRay(
                        client,
                        eye,
                        look,
                        range,
                        entity ->
                                entity.canBeCollidedWith()
                                        && !(entity
                                                        instanceof
                                                        net.minecraft.entity.player.EntityPlayer
                                                                spectator
                                                && spectator.isSpectator()),
                        throughBlocks);
        if (!Targeting.isConfiguredTarget(
                        client, target, TARGET_PLAYERS.get(), false, targetEntityTypes)
                || client.theWorld.getEntityByID(target.getEntityId()) != target) return null;
        // A nearby corner is insufficient: the actual view ray must enter the
        // unexpanded hitbox before its vanilla-range endpoint.
        return CombatGeometry.traceEntity(client, eye, look, range, target, throughBlocks)
                        == RaytraceUtils.EntityRayState.HIT
                ? target
                : null;
    }

    private static double safeInteractionRange(Minecraft client) {
        return Math.max(0.0D, CombatReach.vanillaEntityInteractionRange(client.thePlayer));
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        if (newEnabled) {
            CombatModuleCoordinator.beforeEnable(client, CombatModuleCoordinator.Role.TRIGGER_BOT);
        }
        ENABLED.set(newEnabled);
        missedCrosshair = true;
        missDelayDeadlineNanos = 0L;
        ClientChat.send(
                client,
                "TriggerBot "
                        + statusText()
                        + ". Miss delay: "
                        + formatMissDelayRange()
                        + "s, through block: "
                        + (THROUGH_BLOCK_ENABLED.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public static int setMissDelayRange(Minecraft client, String rawRange) {
        ChargeRange parsedRange =
                parseRange(rawRange, MIN_MISS_DELAY.getMin(), MAX_MISS_DELAY.getMax());
        if (parsedRange == null) {
            ClientChat.send(
                    client,
                    "Invalid TriggerBot miss delay. Use .moons triggerbot miss x-x, where each x is seconds between 0 and 2, for example .moons triggerbot miss 0.05-0.2.");
            return 0;
        }
        MIN_MISS_DELAY.set(parsedRange.min());
        MAX_MISS_DELAY.set(parsedRange.max());
        missedCrosshair = true;
        missDelayDeadlineNanos = 0L;
        ClientChat.send(
                client, "TriggerBot miss delay range set to " + formatMissDelayRange() + "s.");
        return 1;
    }

    public static JsonArray selectedEntities() {
        return RegistryLists.entityIds(targetEntityTypes);
    }

    public static void setSelectedEntities(Minecraft client, JsonElement value) {
        var next = RegistryLists.readEntityIds(value);
        targetEntityTypes.clear();
        targetEntityTypes.addAll(next);
        TARGET_ENTITIES.set(Targeting.serializeEntityTypeIds(targetEntityTypes));
        rejectCameraRay(client);
    }

    private static int showTargetStatus(Minecraft client) {
        ClientChat.send(
                client,
                "TriggerBot targets: "
                        + Targeting.configuredTargetStatus(
                                TARGET_PLAYERS.get(), false, targetEntityTypes)
                        + ". Usage: .moons triggerbot target <add|remove> <player|mob|all|entity id>.");
        return 1;
    }

    public static int setTargetCategory(Minecraft client, String category, boolean add) {
        if ("player".equals(category) || "all".equals(category)) TARGET_PLAYERS.set(add);
        if ("mob".equals(category) || "all".equals(category))
            setSelectedEntities(
                    client, RegistryLists.entityIds(add ? RegistryLists.allMobIds() : Set.of()));
        rejectCameraRay(client);
        return showTargetStatus(client);
    }

    private static ChargeRange parseRange(
            String rawRange, double minSupported, double maxSupported) {
        String normalizedRange = rawRange.trim().toLowerCase(Locale.ROOT);
        String[] rangeParts = normalizedRange.split("-", -1);
        if (rangeParts.length != 1 && rangeParts.length != 2) {
            return null;
        }

        try {
            double parsedMin = Double.parseDouble(rangeParts[0]);
            double parsedMax =
                    rangeParts.length == 1 ? parsedMin : Double.parseDouble(rangeParts[1]);

            if (parsedMin > parsedMax) {
                double swap = parsedMin;
                parsedMin = parsedMax;
                parsedMax = swap;
            }

            if (!Double.isFinite(parsedMin)
                    || !Double.isFinite(parsedMax)
                    || parsedMin < minSupported
                    || parsedMax > maxSupported) {
                return null;
            }

            return new ChargeRange(parsedMin, parsedMax);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
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

    private record ChargeRange(double min, double max) {}
}
