package com.blanoir.tempos.client.module.impl.combat;

import static com.blanoir.tempos.client.module.framework.SettingFactory.rangeText;
import static com.blanoir.tempos.client.utils.time.TimeUnits.NANOS_PER_SECOND;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.StringSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.manager.combat.CombatGeometry;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.blanoir.tempos.client.module.impl.combat.critical.Critical;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.prediction.CooldownPrediction;
import com.blanoir.tempos.client.utils.raytrace.EntityRaycast;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Attacks when a valid target is under the crosshair and the attack is ready.")
public final class TriggerBot extends ClientFeature {
    private static final String MIN_CHARGE_KEY = "triggerbot.minCharge";
    private static final String MAX_CHARGE_KEY = "triggerbot.maxCharge";
    public static final TriggerBot INSTANCE = new TriggerBot();

    private static final double DEFAULT_MAX_CHARGE = 1.0D;

    private final StringSetting targetEntities =
            new StringSetting.Builder().name("triggerbot.target.entities").defaultValue("").build();
    private final StringSetting additionalItems =
            new StringSetting.Builder().name("triggerbot.additionalItems").defaultValue("").build();
    private volatile Set<Identifier> additionalItemIds = loadAdditionalItemIds();

    private double nextAttackCharge = DEFAULT_MAX_CHARGE;
    private int fullChargeTicks = 0;
    private int sampledChargeTick = Integer.MIN_VALUE;
    private double sampledAttackCharge;
    private boolean missedCrosshair = true;
    private long missDelayDeadlineNanos = 0L;
    private final Set<Identifier> targetEntityTypes =
            Targeting.parseEntityTypeIds(this.targetEntities.get());

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("triggerbot.enabled").defaultValue(false).build();

    private final DoubleSetting minCharge =
            new DoubleSetting.Builder()
                    .name(MIN_CHARGE_KEY)
                    .defaultValue(0.7D)
                    .range(0.7D, 1.3D)
                    .build();

    private final DoubleSetting maxCharge =
            new DoubleSetting.Builder()
                    .name(MAX_CHARGE_KEY)
                    .defaultValue(1.0D)
                    .range(0.7D, 1.3D)
                    .build();

    private final DoubleSetting minMissDelay =
            new DoubleSetting.Builder()
                    .name("triggerbot.minMissDelaySeconds")
                    .defaultValue(0.0D)
                    .range(0.0D, 2.0D)
                    .build();

    private final DoubleSetting maxMissDelay =
            new DoubleSetting.Builder()
                    .name("triggerbot.maxMissDelaySeconds")
                    .defaultValue(0.0D)
                    .range(0.0D, 2.0D)
                    .build();

    private final BooleanSetting throughBlockEnabled =
            new BooleanSetting.Builder()
                    .name("triggerbot.throughBlock.enabled")
                    .defaultValue(false)
                    .build();

    private final BooleanSetting targetPlayers =
            new BooleanSetting.Builder()
                    .name("triggerbot.target.player")
                    .defaultValue(true)
                    .build();

    private TriggerBot() {
        super("triggerbot", "TriggerBot");
    }

    /** Result of the camera ray -> cooldown -> Critical -> vanilla-click pipeline. */
    private record AutomaticAttackResult(boolean attacked, String gate) {}

    @Override
    public void registerEvents() {
        EventBus.PLAYER_UPDATE.register(
                "TriggerBot.playerUpdate", event -> playerUpdate(event.client()));
    }

    /** Samples the current view ray and submits before LocalPlayer movement. */
    private void playerUpdate(Minecraft client) {
        if (CombatModuleCoordinator.automationSuspended()
                || client == null
                || client.player == null
                || client.level == null
                || client.gameMode == null
                || MinecraftClientAccess.screen(client) != null) {
            return;
        }
        if (SilentAuraRuntime.activationHeld(client)) {
            return;
        }
        if (!this.enabled.get()) return;
        // Pick, range checks and Critical's forecast share Misplace's displayed geometry.
        playerUpdateCameraRay(client);
    }

    /** Ordinary TriggerBot entry: use the player's current view within vanilla reach. */
    private void playerUpdateCameraRay(Minecraft client) {
        if (!isHoldingSupportedItem(client)) {
            rejectCameraRay(client);
            return;
        }

        // Cooldown/overcharge is a property of the held weapon, not of target
        // visibility. Advancing it only after acquiring a ray made a fully
        // charged player wait again after jumping into melee range.
        attackCharge(client);

        Entity target = getAttackableCrosshairTarget(client);
        if (target == null || CombatGeometry.outsideVanillaRange(client, target)) {
            rejectCameraRay(client);
            return;
        }

        if (isWaitingForMissDelay()) {
            Critical.INSTANCE.cancelAutomaticPrediction(client);
            return;
        }

        if (isPlayerPhysicallyAttacking(client)) {
            Critical.INSTANCE.cancelAutomaticPrediction(client);
            return;
        }

        // Critical owns an already queued attack. Asking it again used
        // to return DEFERRED every tick and reset TriggerBot's charge/threshold
        // state repeatedly, which made Silent attacks occur only occasionally.
        if (Critical.INSTANCE.isAimingWindowActive()) {
            return;
        }

        AutomaticAttackResult result = attackRayTarget(client, target);
        if (result.attacked()) resetAttackState();
    }

    private void rejectCameraRay(Minecraft client) {
        markMissedCrosshair();
        Critical.INSTANCE.cancelAutomaticPrediction(client);
    }

    private void resetAttackState() {
        this.fullChargeTicks = 0;
        this.sampledChargeTick = Integer.MIN_VALUE;
        this.sampledAttackCharge = 0.0D;
        this.missedCrosshair = false;
        this.missDelayDeadlineNanos = 0L;
        this.nextAttackCharge = randomChargeThreshold();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    private int showThroughBlockStatus(Minecraft client) {
        ClientChat.send(
                client,
                "TriggerBot Through Block: "
                        + (this.throughBlockEnabled.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public int setThroughBlockEnabled(Minecraft client, boolean value) {
        this.throughBlockEnabled.set(value);
        markMissedCrosshair();
        return showThroughBlockStatus(client);
    }

    private void markMissedCrosshair() {
        this.missedCrosshair = true;
        this.missDelayDeadlineNanos = 0L;
    }

    private boolean isWaitingForMissDelay() {
        if (!this.missedCrosshair) {
            return false;
        }

        if (this.missDelayDeadlineNanos == 0L) {
            this.missDelayDeadlineNanos = System.nanoTime() + missDelayNanos();
        }

        if (System.nanoTime() < this.missDelayDeadlineNanos) {
            return true;
        }

        this.missedCrosshair = false;
        this.missDelayDeadlineNanos = 0L;
        return false;
    }

    private long missDelayNanos() {
        double delaySeconds = randomMissDelaySeconds();
        return (long) (delaySeconds * NANOS_PER_SECOND);
    }

    private double randomMissDelaySeconds() {
        return RandomRange.between(this.minMissDelay.get(), this.maxMissDelay.get());
    }

    private double attackCharge(Minecraft client) {
        int tick = client.player.tickCount;
        if (this.sampledChargeTick == tick) {
            return this.sampledAttackCharge;
        }
        float cooldownProgress = client.player.getAttackStrengthScale(0.0F);
        if (cooldownProgress < 1.0F) {
            this.fullChargeTicks = 0;
            this.sampledAttackCharge = cooldownProgress;
        } else {
            this.fullChargeTicks++;
            this.sampledAttackCharge =
                    cooldownProgress
                            + this.fullChargeTicks
                                    / client.player.getCurrentItemAttackStrengthDelay();
        }
        this.sampledChargeTick = tick;
        return this.sampledAttackCharge;
    }

    /**
     * The camera-ray entry owns cooldown, Critical
     * state and dispatch after validating the current view ray.
     */
    private AutomaticAttackResult attackRayTarget(Minecraft client, Entity target) {
        if (client == null
                || client.player == null
                || client.level == null
                || client.gameMode == null
                || target == null
                || !target.isAlive()) {
            return new AutomaticAttackResult(false, "invalid");
        }
        if (!isHoldingSupportedItem(client)) {
            return new AutomaticAttackResult(false, "weapon");
        }

        double charge = attackCharge(client);
        boolean criticalAttack = false;
        {
            // Preserve the original TriggerBot + Predict contract: Critical
            // sees how many ticks remain before the sampled charge threshold,
            // so it can align that cooldown with the current jump instead of
            // starting its forecast only after the threshold has elapsed.
            int ticksUntilReady =
                    CooldownPrediction.ticksUntilThreshold(
                            charge,
                            this.nextAttackCharge,
                            client.player.getCurrentItemAttackStrengthDelay());
            Critical.AutomaticAttackGate criticalGate =
                    Critical.INSTANCE.gateAutomaticAttack(client, target, ticksUntilReady);
            if (criticalGate != Critical.AutomaticAttackGate.ALLOW
                    && criticalGate != Critical.AutomaticAttackGate.ATTACK) {
                return new AutomaticAttackResult(
                        false, "critical " + criticalGate.name().toLowerCase(Locale.ROOT));
            }
            criticalAttack = criticalGate == Critical.AutomaticAttackGate.ATTACK;
        }

        // ATTACK is the current-structure equivalent of old Predict ATTACKED:
        // the reservation already counted down its cooldown lead, so sampling
        // TriggerBot's threshold a second time here would weaken old behavior.
        if (!criticalAttack && charge + 1.0E-4D < this.nextAttackCharge) {
            return new AutomaticAttackResult(
                    false,
                    String.format(Locale.ROOT, "charge %.2f/%.2f", charge, this.nextAttackCharge));
        }

        var hit =
                CombatGeometry.attackHit(
                        client,
                        target,
                        client.player.getEyePosition(),
                        client.player.getViewVector(1.0F),
                        safeInteractionRange(client));
        if (hit == null) return new AutomaticAttackResult(false, "target moved");
        boolean attacked =
                HitSelect.INSTANCE.withoutFiltering(
                        () -> CombatInputController.attackTargetNow(client, hit, true));
        if (!attacked) {
            return new AutomaticAttackResult(false, "attack dispatch");
        }

        Critical.INSTANCE.cancelAutomaticPrediction(client);
        return new AutomaticAttackResult(true, "attack");
    }

    private static boolean isPlayerPhysicallyAttacking(Minecraft client) {
        return PhysicalInput.isGameplayKeyDown(client, client.options.keyAttack);
    }

    private Entity getAttackableCrosshairTarget(Minecraft client) {
        if (client == null || client.player == null || client.level == null) return null;
        return crosshairTarget(client, safeInteractionRange(client));
    }

    /** Read-only acquisition for movement; attack reach and dispatch retain their own gate. */
    public Entity movementTarget(Minecraft client, double range) {
        return isEnabled() && isHoldingSupportedItem(client)
                ? crosshairTarget(client, range)
                : null;
    }

    public boolean isMovementTarget(Minecraft client, Entity target) {
        return isEnabled()
                && isHoldingSupportedItem(client)
                && Targeting.isConfiguredTarget(
                        client, target, this.targetPlayers.get(), false, this.targetEntityTypes);
    }

    private Entity crosshairTarget(Minecraft client, double range) {
        if (client == null || client.player == null || client.level == null) return null;
        var eye = client.player.getEyePosition();
        var look = client.player.getViewVector(1.0F);
        boolean throughBlocks = this.throughBlockEnabled.get();
        // The shared camera hit can be stale or extended by Reach. Pick afresh,
        // retaining non-target entities as occluders instead of looking through them.
        Entity target =
                CombatGeometry.findTargetOnRay(
                        client, eye, look, range, EntitySelector.CAN_BE_PICKED, throughBlocks);
        if (!Targeting.isConfiguredTarget(
                        client, target, this.targetPlayers.get(), false, this.targetEntityTypes)
                || client.level.getEntity(target.getId()) != target) return null;
        // A nearby corner is insufficient: the actual view ray must enter the
        // unexpanded hitbox before its vanilla-range endpoint.
        return CombatGeometry.traceEntity(client, eye, look, range, target, throughBlocks)
                        == EntityRaycast.EntityRayState.HIT
                ? target
                : null;
    }

    private static double safeInteractionRange(Minecraft client) {
        return Math.max(0.0D, CombatReach.vanillaEntityInteractionRange(client.player));
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        if (newEnabled) {
            CombatModuleCoordinator.beforeEnable(client, CombatModuleCoordinator.Role.TRIGGER_BOT);
        }
        this.enabled.set(newEnabled);
        this.fullChargeTicks = 0;
        this.sampledChargeTick = Integer.MIN_VALUE;
        this.sampledAttackCharge = 0.0D;
        this.missedCrosshair = true;
        this.missDelayDeadlineNanos = 0L;
        this.nextAttackCharge = randomChargeThreshold();
        ClientChat.send(
                client,
                "TriggerBot "
                        + statusText()
                        + ". Charge range: "
                        + formatChargeRange()
                        + ", miss delay: "
                        + formatMissDelayRange()
                        + "s, through block: "
                        + (this.throughBlockEnabled.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public int setChargeRange(Minecraft client, String rawRange) {
        ChargeRange parsedRange = parseChargeRange(rawRange);
        if (parsedRange == null) {
            ClientChat.send(
                    client,
                    "Invalid TriggerBot range. Use .tempos Triggerbot x-x, where each x is between 0.7 and 1.3, for example .tempos triggerbot 0.7-1.3.");
            return 0;
        }
        this.minCharge.set(parsedRange.min());
        this.maxCharge.set(parsedRange.max());
        this.sampledChargeTick = Integer.MIN_VALUE;
        this.nextAttackCharge = randomChargeThreshold();
        ClientChat.send(client, "TriggerBot charge range set to " + formatChargeRange() + ".");
        return 1;
    }

    public int setMissDelayRange(Minecraft client, String rawRange) {
        ChargeRange parsedRange =
                parseRange(rawRange, this.minMissDelay.getMin(), this.maxMissDelay.getMax());
        if (parsedRange == null) {
            ClientChat.send(
                    client,
                    "Invalid TriggerBot miss delay. Use .tempos triggerbot miss x-x, where each x is seconds between 0 and 2, for example .tempos triggerbot miss 0.05-0.2.");
            return 0;
        }
        this.minMissDelay.set(parsedRange.min());
        this.maxMissDelay.set(parsedRange.max());
        this.missedCrosshair = true;
        this.missDelayDeadlineNanos = 0L;
        ClientChat.send(
                client, "TriggerBot miss delay range set to " + formatMissDelayRange() + "s.");
        return 1;
    }

    public JsonArray selectedEntities() {
        return SettingFactory.entityIds(this.targetEntityTypes);
    }

    private boolean isHoldingSupportedItem(Minecraft client) {
        if (Targeting.isHoldingTriggerWeapon(client)) return true;
        return client != null
                && client.player != null
                && isAdditionalItem(client.player.getMainHandItem());
    }

    private boolean isAdditionalItem(ItemStack stack) {
        return !stack.isEmpty()
                && this.additionalItemIds.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private Set<Identifier> loadAdditionalItemIds() {
        Set<Identifier> result = new LinkedHashSet<>();
        // Retain syntactically valid mod IDs when a later launch omits that mod.
        for (String token : this.additionalItems.get().split("[,;\\s]+")) {
            if (token.isBlank()) continue;
            Identifier id = Identifier.tryParse(token);
            if (id != null && !id.toString().equals("minecraft:air")) result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }

    public JsonArray additionalItems() {
        JsonArray result = new JsonArray();
        this.additionalItemIds.forEach(id -> result.add(SettingFactory.entry(id.toString(), null)));
        return result;
    }

    public void setAdditionalItems(Minecraft client, JsonElement value) {
        Set<Identifier> next = new LinkedHashSet<>();
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Invalid additional item list");
        }
        for (JsonElement element : value.getAsJsonArray()) {
            // Previously saved IDs may be absent in this mod profile. Keep them removable
            // without accepting new unknown IDs from the editor/API.
            if (!element.isJsonObject()
                    || !element.getAsJsonObject().has("id")
                    || !element.getAsJsonObject().get("id").isJsonPrimitive()
                    || !element.getAsJsonObject().get("id").getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Invalid additional item list");
            }
            Identifier id = Identifier.tryParse(element.getAsJsonObject().get("id").getAsString());
            if (id == null
                    || id.toString().equals("minecraft:air")
                    || !next.add(id)
                    || !this.additionalItemIds.contains(id)
                            && BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
                throw new IllegalArgumentException("Invalid additional item ID");
            }
        }
        this.additionalItems.set(
                next.stream().map(Identifier::toString).collect(Collectors.joining(",")));
        this.additionalItemIds = Collections.unmodifiableSet(next);
        rejectCameraRay(client);
    }

    public void setSelectedEntities(Minecraft client, JsonElement value) {
        var next = SettingFactory.readEntityIds(value);
        this.targetEntityTypes.clear();
        this.targetEntityTypes.addAll(next);
        this.targetEntities.set(Targeting.serializeEntityTypeIds(this.targetEntityTypes));
        rejectCameraRay(client);
    }

    private int showTargetStatus(Minecraft client) {
        ClientChat.send(
                client,
                "TriggerBot targets: "
                        + Targeting.configuredTargetStatus(
                                this.targetPlayers.get(), false, this.targetEntityTypes)
                        + ". Usage: .tempos triggerbot target <add|remove> <player|mob|all|entity id>.");
        return 1;
    }

    public int setTargetCategory(Minecraft client, String category, boolean add) {
        if ("player".equals(category) || "all".equals(category)) this.targetPlayers.set(add);
        if ("mob".equals(category) || "all".equals(category))
            setSelectedEntities(
                    client, SettingFactory.entityIds(add ? SettingFactory.allMobIds() : Set.of()));
        rejectCameraRay(client);
        return showTargetStatus(client);
    }

    private ChargeRange parseChargeRange(String rawRange) {
        return parseRange(rawRange, this.minCharge.getMin(), this.maxCharge.getMax());
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

    private double randomChargeThreshold() {
        return RandomRange.between(this.minCharge.get(), this.maxCharge.get());
    }

    private String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    private String formatChargeRange() {
        return formatDouble(this.minCharge.get()) + "-" + formatDouble(this.maxCharge.get());
    }

    private String formatMissDelayRange() {
        return formatDouble(this.minMissDelay.get()) + "-" + formatDouble(this.maxMissDelay.get());
    }

    private static String formatDouble(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }

    private record ChargeRange(double min, double max) {}

    @Override
    protected String statusTag() {
        return rangeText(MIN_CHARGE_KEY, MAX_CHARGE_KEY, .7, 1.3);
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            minCharge.describeStoredRange(
                    "charge",
                    "Attack charge",
                    maxCharge,
                    maxCharge.getMax(),
                    .01,
                    SettingFactory.formattedRange(this::setChargeRange)),
            minMissDelay.describeStoredRange(
                    "miss_delay",
                    "Miss delay",
                    maxMissDelay,
                    .01,
                    SettingFactory.formattedRange(this::setMissDelayRange)),
            throughBlockEnabled.describeStored(
                    "through_block", "Through block", this::setThroughBlockEnabled),
            SettingFactory.registryList(
                    "additional_items",
                    "Additional items",
                    "item",
                    this::additionalItems,
                    this::setAdditionalItems,
                    new com.google.gson.JsonArray()),
            targetPlayers.describeStored(
                    "target_players",
                    "Target players",
                    (client, value) -> this.setTargetCategory(client, "player", value)),
            SettingFactory.registryList(
                    "target_entities",
                    "Other entities",
                    "mob",
                    this::selectedEntities,
                    this::setSelectedEntities,
                    new com.google.gson.JsonArray())
        };
    }
}
