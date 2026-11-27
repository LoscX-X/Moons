/*
 * AutoTotem for Tempos.
 *
 * Switches a
 * totem of undying into the offhand once when a new danger episode begins.
 * The selected totem remains in the offhand after the player becomes safe.
 * Defaults to lethal damage predicted within ten ticks. Legacy health/armor
 * thresholds are available only through the optional health fallback.
 */
package com.blanoir.tempos.client.module.impl.player;


import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.inventory.InventoryClickFailure;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.prediction.DamagePrediction;
import com.blanoir.tempos.client.utils.prediction.LandingPrediction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@ModuleInfo(
        category = ModuleCategory.PLAYER,
        description = "Equips a totem in your offhand according to the selected conditions.")
public final class AutoTotem extends ClientFeature {
    public static final AutoTotem INSTANCE = new AutoTotem();

    private final InventoryClickFailure failure = new InventoryClickFailure("autototem");

    public com.blanoir.tempos.client.module.framework.ModuleModel.Setting failureSetting() {
        return this.failure.setting();
    }

    private static final long RETRY_DELAY_MS = 200L;
    private static final int EXPLOSION_SPHERE_RADIUS = 10;

    private final EquipmentSlot[] armorSlots = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private final Direction[] horizontalDirections = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("autototem.enabled").defaultValue(false).build();

    private final IntSetting healthThreshold =
            new IntSetting.Builder()
                    .name("autototem.threshold")
                    .defaultValue(8)
                    .range(0, 20)
                    .build();

    private final BooleanSetting healthFallback =
            new BooleanSetting.Builder()
                    .name("autototem.healthfallback")
                    .defaultValue(false)
                    .build();

    private final IntSetting switchDelayMs =
            new IntSetting.Builder()
                    .name("autototem.switchdelay")
                    .defaultValue(0)
                    .range(0, 500)
                    .build();

    private final BooleanSetting missingArmor =
            new BooleanSetting.Builder().name("autototem.missingarmor").defaultValue(false).build();

    private final BooleanSetting safetyEnabled =
            new BooleanSetting.Builder().name("autototem.safety").defaultValue(true).build();

    private final IntSetting safeThreshold =
            new IntSetting.Builder()
                    .name("autototem.safethreshold")
                    .defaultValue(10)
                    .range(0, 20)
                    .build();

    private final BooleanSetting subtractCalculatedDamage =
            new BooleanSetting.Builder()
                    .name("autototem.subtractdamage")
                    .defaultValue(false)
                    .build();

    private final BooleanSetting predictExplosionEntities =
            new BooleanSetting.Builder()
                    .name("autototem.explosionentities")
                    .defaultValue(true)
                    .build();

    private final BooleanSetting predictExplosionBlocks =
            new BooleanSetting.Builder()
                    .name("autototem.explosionblocks")
                    .defaultValue(false)
                    .build();

    private final BooleanSetting predictFallDamage =
            new BooleanSetting.Builder().name("autototem.falldamage").defaultValue(true).build();

    private final BooleanSetting ignoreElytra =
            new BooleanSetting.Builder()
                    .name("autototem.fallignoreelytra")
                    .defaultValue(false)
                    .build();

    private BlockPos[] explosionSphere;
    private final AutoTotemDanger damageHistory = new AutoTotemDanger();
    private String predictionReason = "none";

    private boolean dangerEpisodeHandled;
    private boolean offhandTotemObserved;
    private int sourceMenuSlot = -1;
    private long nextActionAtMs;
    private InventoryClickStep inventoryClickStep = InventoryClickStep.NONE;
    private boolean inventoryOpenedByModule;
    private boolean transactionOffhandEmpty;
    private final Object inventoryOwner = new Object();

    private enum InventoryClickStep {
        NONE,
        EQUIP_PICKUP_TOTEM,
        EQUIP_PLACE_OFFHAND,
        EQUIP_RETURN_OLD_ITEM
    }

    private AutoTotem() {
        super("autototem", "AutoTotem");
    }

    public boolean inventoryBusy() {
        return this.inventoryClickStep != InventoryClickStep.NONE || this.inventoryOpenedByModule;
    }

    public boolean reservesOffhand() {
        return this.enabled.get() || inventoryBusy();
    }

    @Override
    public void registerEvents() {
        com.blanoir.tempos.client.manager.inventory.InventoryClicks.init();
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "AutoTotem.inventoryContext", event -> resetState());
        // AutoTotem is edge-triggered and never restores the previous offhand.

        EventBus.TICK.register(
                "AutoTotem.tick",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private void tick(Minecraft client) {
        if (!coreReady(client)) {
            closeModuleInventory(client);
            resetState();
            return;
        }

        if (this.enabled.get()) {
            this.damageHistory.sample(
                    client.player.tickCount,
                    client.player.getHealth(),
                    client.player.getAbsorptionAmount(),
                    client.player.hurtTime);
        }

        // Once a pickup sequence starts, finish it even if the module is
        // toggled off mid-swap. Leaving an item on the carried cursor is worse
        // than completing the already-authorized inventory action.
        if (this.inventoryClickStep != InventoryClickStep.NONE) {
            tickInventoryClicks(client);
            return;
        }

        if (!this.enabled.get()) {
            resetState();
            return;
        }

        if (!inventoryScreenAvailable(client)) {
            return;
        }

        boolean danger = predictedDanger(client);
        boolean offhandHasTotem =
                client.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
        boolean equippedTotemWasRemoved = this.offhandTotemObserved && !offhandHasTotem;
        this.offhandTotemObserved = offhandHasTotem;

        if (!danger) {
            // A safe interval rearms the module for the next distinct danger
            // episode. The current offhand item is intentionally left alone.
            this.dangerEpisodeHandled = false;
            return;
        }

        // A totem that was present and is now gone (usually after activation)
        // exposes the player to lethal damage again, so allow one replacement.
        if (equippedTotemWasRemoved) {
            this.dangerEpisodeHandled = false;
        }

        if (this.dangerEpisodeHandled) {
            return;
        }

        if (offhandHasTotem) {
            this.dangerEpisodeHandled = true;
            this.offhandTotemObserved = true;
            return;
        }

        if (System.currentTimeMillis() < this.nextActionAtMs) {
            return;
        }

        if (com.blanoir.tempos.client.manager.inventory.InventoryClicks.busyExcept(
                this.inventoryOwner)) {
            com.blanoir.tempos.client.manager.inventory.InventoryClicks.requestPreemption();
            return;
        }
        if (client.player.containerMenu != client.player.inventoryMenu
                || !client.player.inventoryMenu.getCarried().isEmpty()) return;
        equipTotem(client);
    }

    private void equipTotem(Minecraft client) {
        int containerId = client.player.inventoryMenu.containerId;
        int inventorySlot = findTotemSlot(client.player.getInventory());
        if (inventorySlot >= 0
                && this.failure.beforeClick(
                        client,
                        client.player.inventoryMenu,
                        inventorySlot < 9
                                ? Inventory.INVENTORY_SIZE + inventorySlot
                                : inventorySlot,
                        this.inventoryOwner)) {
            this.nextActionAtMs =
                    System.currentTimeMillis() + Math.max(50, this.switchDelayMs.get());
            return;
        }

        if (inventorySlot >= 0 && inventorySlot < 9) {
            int menuSlot = Inventory.INVENTORY_SIZE + inventorySlot;
            client.gameMode.handleContainerInput(
                    containerId, menuSlot, 40, ContainerInput.SWAP, client.player);
            this.dangerEpisodeHandled = true;
            this.nextActionAtMs = System.currentTimeMillis() + this.switchDelayMs.get();
            return;
        }

        if (inventorySlot == -1) {
            this.nextActionAtMs =
                    System.currentTimeMillis() + Math.max(this.switchDelayMs.get(), RETRY_DELAY_MS);
            return;
        }

        this.sourceMenuSlot = inventorySlot;
        this.transactionOffhandEmpty = client.player.getOffhandItem().isEmpty();
        beginInventoryClicks(client, InventoryClickStep.EQUIP_PICKUP_TOTEM);
    }

    private void beginInventoryClicks(Minecraft client, InventoryClickStep firstStep) {
        if (!com.blanoir.tempos.client.manager.inventory.InventoryClicks.acquire(
                this.inventoryOwner)) return;
        if (MinecraftClientAccess.screen(client) == null) {
            MinecraftClientAccess.setScreen(client, new InventoryScreen(client.player));
            this.inventoryOpenedByModule = true;
        } else if (MinecraftClientAccess.screen(client) instanceof InventoryScreen) {
            this.inventoryOpenedByModule = false;
        } else {
            com.blanoir.tempos.client.manager.inventory.InventoryClicks.release(this.inventoryOwner);
            return;
        }

        this.inventoryClickStep = firstStep;
        this.nextActionAtMs = System.currentTimeMillis() + this.switchDelayMs.get();
    }

    private void tickInventoryClicks(Minecraft client) {
        if (!(MinecraftClientAccess.screen(client) instanceof InventoryScreen)) {
            if (MinecraftClientAccess.screen(client) != null) {
                // Do not overwrite chat, pause menus, or another container.
                return;
            }
            MinecraftClientAccess.setScreen(client, new InventoryScreen(client.player));
            this.inventoryOpenedByModule = true;
            return;
        }

        long now = System.currentTimeMillis();
        if (now < this.nextActionAtMs) {
            return;
        }

        switch (this.inventoryClickStep) {
            case EQUIP_PICKUP_TOTEM -> {
                if (this.sourceMenuSlot < 0
                        || client.player
                                        .inventoryMenu
                                        .getSlot(this.sourceMenuSlot)
                                        .getItem()
                                        .getItem()
                                != Items.TOTEM_OF_UNDYING) {
                    abortInventoryClicks(client);
                    return;
                }
                clickInventorySlot(client, this.sourceMenuSlot);
                this.inventoryClickStep = InventoryClickStep.EQUIP_PLACE_OFFHAND;
            }
            case EQUIP_PLACE_OFFHAND -> {
                clickInventorySlot(client, InventoryMenu.SHIELD_SLOT);
                if (this.transactionOffhandEmpty) {
                    finishTotemEquip(client);
                    return;
                }
                this.inventoryClickStep = InventoryClickStep.EQUIP_RETURN_OLD_ITEM;
            }
            case EQUIP_RETURN_OLD_ITEM -> {
                clickInventorySlot(client, this.sourceMenuSlot);
                finishTotemEquip(client);
                return;
            }
            case NONE -> {
                return;
            }
        }

        // Even with switchDelay=0, only one click is sent per client tick.
        this.nextActionAtMs = now + this.switchDelayMs.get();
    }

    private static void clickInventorySlot(Minecraft client, int slot) {
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                slot,
                0,
                ContainerInput.PICKUP,
                client.player);
    }

    private void finishTotemEquip(Minecraft client) {
        com.blanoir.tempos.client.manager.inventory.InventoryClicks.release(this.inventoryOwner);
        this.inventoryClickStep = InventoryClickStep.NONE;
        this.dangerEpisodeHandled = true;
        this.sourceMenuSlot = -1;
        this.transactionOffhandEmpty = false;
        this.nextActionAtMs = System.currentTimeMillis() + this.switchDelayMs.get();
        closeModuleInventory(client);
    }

    private void abortInventoryClicks(Minecraft client) {
        this.inventoryClickStep = InventoryClickStep.NONE;
        closeModuleInventory(client);
        resetState();
        this.nextActionAtMs =
                System.currentTimeMillis() + Math.max(this.switchDelayMs.get(), RETRY_DELAY_MS);
    }

    private void closeModuleInventory(Minecraft client) {
        if (this.inventoryOpenedByModule
                && MinecraftClientAccess.screen(client) instanceof InventoryScreen) {
            MinecraftClientAccess.setScreen(client, null);
        }
        this.inventoryOpenedByModule = false;
    }

    /** Inventory order keeps hotbar totems ahead of main-inventory totems. */
    private static int findTotemSlot(Inventory inventory) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean coreReady(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        return client != null
                && currentPlayer != null
                && client.level != null
                && client.gameMode != null
                && !currentPlayer.isCreative()
                && !currentPlayer.isSpectator()
                && !currentPlayer.isDeadOrDying();
    }

    private static boolean inventoryScreenAvailable(Minecraft client) {
        return MinecraftClientAccess.screen(client) == null
                || MinecraftClientAccess.screen(client) instanceof InventoryScreen;
    }

    private static float playerHealth(Minecraft client) {
        return client.player.getHealth() + client.player.getAbsorptionAmount();
    }

    private boolean predictedDanger(Minecraft client) {
        this.predictionReason = "none";
        float health = playerHealth(client);
        boolean fallback = this.healthFallback.get();
        int threshold = this.healthThreshold.get();
        if (fallback) {
            if (this.safetyEnabled.get() && (isBurrowed(client) || isInHole(client))) {
                threshold = Math.min(threshold, this.safeThreshold.get());
            }
            if (health <= threshold || (this.missingArmor.get() && hasMissingArmor(client))) {
                this.predictionReason = "health/armor fallback";
                return true;
            }
        }
        float allowedDamage =
                AutoTotemDanger.damageBudget(
                        health, fallback, this.subtractCalculatedDamage.get(), threshold);
        float calculatedDamage = explosionDamageFromEntities(client, allowedDamage);
        if (AutoTotemDanger.lethal(allowedDamage, calculatedDamage)) {
            this.predictionReason = "explosion <=10 ticks";
            return true;
        }
        calculatedDamage =
                Math.max(calculatedDamage, explosionDamageFromBlocks(client, allowedDamage));
        if (AutoTotemDanger.lethal(allowedDamage, calculatedDamage)) {
            this.predictionReason = "instant explosive block";
            return true;
        }
        // History may describe the same explosion: never count it twice.
        calculatedDamage =
                Math.max(
                        calculatedDamage,
                        this.damageHistory.incomingDamage(client.player.tickCount));
        calculatedDamage += fallDamage(client);
        if (!AutoTotemDanger.lethal(allowedDamage, calculatedDamage)) return false;
        this.predictionReason = "fall/repeated damage <=10 ticks";
        return true;
    }

    private boolean hasMissingArmor(Minecraft client) {
        for (EquipmentSlot slot : this.armorSlots) {
            if (client.player.getItemBySlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBurrowed(Minecraft client) {
        return isBlastResistant(client, feetBlockPos(client));
    }

    private boolean isInHole(Minecraft client) {
        BlockPos feet = feetBlockPos(client);
        for (Direction direction : this.horizontalDirections) {
            if (!isBlastResistant(client, feet.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private static BlockPos feetBlockPos(Minecraft client) {
        AABB box = client.player.getBoundingBox();
        return new BlockPos(
                Mth.floor(Mth.lerp(0.5D, box.minX, box.maxX)),
                Mth.ceil(box.minY),
                Mth.floor(Mth.lerp(0.5D, box.minZ, box.maxZ)));
    }

    private static boolean isBlastResistant(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos).getBlock().getExplosionResistance() >= 600.0F;
    }

    private float explosionDamageFromEntities(Minecraft client, float allowedDamage) {
        if (!this.predictExplosionEntities.get()) {
            return 0.0F;
        }

        float maxDamage = 0.0F;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!AutoTotemDanger.withinWindow(DamagePrediction.explosionTicks(entity))) continue;
            maxDamage =
                    Math.max(maxDamage, DamagePrediction.explosionDamageFromEntity(client, entity));
            if (maxDamage >= allowedDamage) {
                return maxDamage;
            }
        }
        return maxDamage;
    }

    private float explosionDamageFromBlocks(Minecraft client, float allowedDamage) {
        if (!this.predictExplosionBlocks.get()) {
            return 0.0F;
        }

        BlockPos[] sphere = this.explosionSphere();
        boolean overworld = !bedExplodes(client);
        boolean respawnAnchorWorks = respawnAnchorWorks(client);
        BlockPos playerPos = client.player.blockPosition();
        float maxDamage = 0.0F;

        for (BlockPos offset : sphere) {
            BlockPos pos = playerPos.offset(offset);
            BlockState state = client.level.getBlockState(pos);
            Block block = state.getBlock();
            boolean bed = block instanceof BedBlock;
            boolean anchor = block instanceof RespawnAnchorBlock && isCharged(state);

            if ((overworld || !bed) && (respawnAnchorWorks || !anchor)) {
                continue;
            }

            Vec3 center = center(pos);
            float damage =
                    DamagePrediction.explosionDamage(
                            client,
                            center,
                            5.0F,
                            10.0F,
                            100.0F,
                            client.player.damageSources().badRespawnPointExplosion(center));
            maxDamage = Math.max(maxDamage, damage);
            if (maxDamage >= allowedDamage) {
                return maxDamage;
            }
        }

        return maxDamage;
    }

    private float fallDamage(Minecraft client) {
        Player player = client.player;
        if (player == null
                || !this.predictFallDamage.get()
                || player.onGround()
                || player.getDeltaMovement().y >= 0
                || player.isInWater()
                || player.isInLava()
                || player.onClimbable()
                || player.getAbilities().flying
                || player.hasEffect(MobEffects.LEVITATION)
                || player.hasEffect(MobEffects.SLOW_FALLING)) {
            return 0.0F;
        }

        if (this.ignoreElytra.get() && player.isFallFlying() && player.hasPose(Pose.FALL_FLYING)) {
            return 0.0F;
        }

        // Glide lift depends on future steering: only trust an imminent contact in that state.
        var impact =
                LandingPrediction.fallImpact(
                        player, player.isFallFlying() ? 1 : AutoTotemDanger.HORIZON_TICKS);
        if (impact == null) return 0;
        BlockPos landing = LandingPrediction.supportBlock(player, impact.box());
        float multiplier = DamagePrediction.fallDamageMultiplier(client, landing);
        if (landing != null
                && client.level
                        .getBlockState(landing)
                        .is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)
                && !player.isSuppressingBounce()) multiplier = 0;
        return DamagePrediction.fallDamage(player, impact.fallDistance(), multiplier);
    }

    private BlockPos[] explosionSphere() {
        if (this.explosionSphere != null) {
            return this.explosionSphere;
        }

        List<BlockPos> offsets = new ArrayList<>();
        int radius = EXPLOSION_SPHERE_RADIUS;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        offsets.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(AutoTotem::offsetDistanceSqr));
        this.explosionSphere = offsets.toArray(new BlockPos[0]);
        return this.explosionSphere;
    }

    private static double offsetDistanceSqr(BlockPos pos) {
        return pos.getX() * pos.getX() + pos.getY() * pos.getY() + pos.getZ() * pos.getZ();
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static boolean bedExplodes(Minecraft client) {
        ResourceKey<Level> dimension = client.level.dimension();
        return Level.NETHER.equals(dimension) || Level.END.equals(dimension);
    }

    private static boolean respawnAnchorWorks(Minecraft client) {
        return Level.NETHER.equals(client.level.dimension());
    }

    private static boolean isCharged(BlockState state) {
        return state.getValue(RespawnAnchorBlock.CHARGE) > 0;
    }

    private void resetState() {
        this.damageHistory.reset();
        this.predictionReason = "none";
        this.failure.reset();
        com.blanoir.tempos.client.manager.inventory.InventoryClicks.release(this.inventoryOwner);
        this.dangerEpisodeHandled = false;
        this.offhandTotemObserved = false;
        this.sourceMenuSlot = -1;
        this.inventoryClickStep = InventoryClickStep.NONE;
        this.inventoryOpenedByModule = false;
        this.transactionOffhandEmpty = false;
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoTotem: "
                        + statusText()
                        + ", prediction: 10 ticks (~0.5s at 20 TPS), reason: "
                        + this.predictionReason
                        + ", healthFallback: "
                        + toggleText(this.healthFallback.get())
                        + ", fallback threshold (HP): "
                        + this.healthThreshold.get()
                        + ", safeThreshold: "
                        + this.safeThreshold.get()
                        + ", missingArmor: "
                        + toggleText(this.missingArmor.get())
                        + ", safety: "
                        + toggleText(this.safetyEnabled.get())
                        + ", subtractDamage: "
                        + toggleText(this.subtractCalculatedDamage.get())
                        + ", explosionEntities: "
                        + toggleText(this.predictExplosionEntities.get())
                        + ", explosionBlocks: "
                        + toggleText(this.predictExplosionBlocks.get())
                        + ", fallDamage: "
                        + toggleText(this.predictFallDamage.get())
                        + ", oneShot: enabled, keepOffhand: enabled"
                        + ". Use module settings or .tempos autototem <setting_id> <value>.");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        if (this.inventoryClickStep == InventoryClickStep.NONE) {
            closeModuleInventory(client);
            resetState();
            this.nextActionAtMs = 0L;
        }
        ClientChat.send(client, "AutoTotem " + statusText() + ".");
        return 1;
    }

    public int setThreshold(Minecraft client, int value) {
        this.healthThreshold.set(value);
        ClientChat.send(client, "AutoTotem threshold set to " + this.healthThreshold.get() + ".");
        return 1;
    }

    public int setHealthFallback(Minecraft client, boolean value) {
        this.healthFallback.set(value);
        ClientChat.send(client, "AutoTotem healthFallback " + toggleText(value) + ".");
        return 1;
    }

    public int setSafeThreshold(Minecraft client, int value) {
        this.safeThreshold.set(value);
        ClientChat.send(client, "AutoTotem safeThreshold set to " + this.safeThreshold.get() + ".");
        return 1;
    }

    public int setMissingArmor(Minecraft client, boolean value) {
        this.missingArmor.set(value);
        ClientChat.send(
                client, "AutoTotem missingArmor " + toggleText(this.missingArmor.get()) + ".");
        return 1;
    }

    public int setSafety(Minecraft client, boolean value) {
        this.safetyEnabled.set(value);
        ClientChat.send(client, "AutoTotem safety " + toggleText(this.safetyEnabled.get()) + ".");
        return 1;
    }

    public int setSubtractDamage(Minecraft client, boolean value) {
        this.subtractCalculatedDamage.set(value);
        ClientChat.send(
                client,
                "AutoTotem subtractDamage "
                        + toggleText(this.subtractCalculatedDamage.get())
                        + ".");
        return 1;
    }

    public int setExplosionEntities(Minecraft client, boolean value) {
        this.predictExplosionEntities.set(value);
        ClientChat.send(
                client,
                "AutoTotem explosionEntities "
                        + toggleText(this.predictExplosionEntities.get())
                        + ".");
        return 1;
    }

    public int setExplosionBlocks(Minecraft client, boolean value) {
        this.predictExplosionBlocks.set(value);
        ClientChat.send(
                client,
                "AutoTotem explosionBlocks " + toggleText(this.predictExplosionBlocks.get()) + ".");
        return 1;
    }

    public int setFallDamage(Minecraft client, boolean value) {
        this.predictFallDamage.set(value);
        ClientChat.send(
                client, "AutoTotem fallDamage " + toggleText(this.predictFallDamage.get()) + ".");
        return 1;
    }

    public int setIgnoreElytra(Minecraft client, boolean value) {
        this.ignoreElytra.set(value);
        ClientChat.send(
                client, "AutoTotem ignoreElytra " + toggleText(this.ignoreElytra.get()) + ".");
        return 1;
    }

    public int setSwitchDelay(Minecraft client, int value) {
        this.switchDelayMs.set(value);
        ClientChat.send(client, "AutoTotem switchDelay set to " + this.switchDelayMs.get() + "ms.");
        return 1;
    }

    private static String toggleText(boolean value) {
        return value ? "enabled" : "disabled";
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("autototem.enabled", false);
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            this.failureSetting(),
            healthFallback.describeStored(
                    "health_fallback", "Health fallback", this::setHealthFallback),
            healthThreshold
                    .describeStored("threshold", "Fallback health (HP)", 1, this::setThreshold)
                    .visibleWhen(healthFallback::storedValue),
            safeThreshold
                    .describeStored(
                            "safe_threshold",
                            "Fallback safe health (HP)",
                            1,
                            this::setSafeThreshold)
                    .visibleWhen(healthFallback::storedValue),
            missingArmor
                    .describeStored(
                            "missing_armor", "Fallback missing armor", this::setMissingArmor)
                    .visibleWhen(healthFallback::storedValue),
            safetyEnabled
                    .describeStored("safety", "Fallback safety", this::setSafety)
                    .visibleWhen(healthFallback::storedValue),
            subtractCalculatedDamage
                    .describeStored(
                            "subtract_damage", "Fallback damage margin", this::setSubtractDamage)
                    .visibleWhen(healthFallback::storedValue),
            predictExplosionEntities.describeStored(
                    "explosion_entities", "Explosion entities", this::setExplosionEntities),
            predictExplosionBlocks.describeStored(
                    "explosion_blocks", "Explosion blocks", this::setExplosionBlocks),
            predictFallDamage.describeStored("fall_damage", "Fall damage", this::setFallDamage),
            ignoreElytra.describeStored("ignore_elytra", "Ignore elytra", this::setIgnoreElytra),
            switchDelayMs.describeStored("switch_delay", "Switch delay", 5, this::setSwitchDelay)
        };
    }
}
