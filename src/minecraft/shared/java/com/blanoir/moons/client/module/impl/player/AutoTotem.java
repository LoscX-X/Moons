/*
 * AutoTotem for Moons.
 *
 * Ported from LiquidBounce (GPL-3.0) ModuleOffhand / Totem.Health: switches a
 * totem of undying into the offhand once when a new danger episode begins.
 * The selected totem remains in the offhand after the player becomes safe.
 * Danger includes low health (+absorption), missing armor,
 * being burrowed/in a hole with a lower safety threshold, predicted explosion
 * damage from entities or beds/respawn anchors, and predicted fall damage.
 */
package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AutoTotem {
    private static final long RETRY_DELAY_MS = 200L;
    private static final int EXPLOSION_SPHERE_RADIUS = 10;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autototem.enabled").defaultValue(false).build();

    private static final IntSetting HEALTH_THRESHOLD =
            new IntSetting.Builder()
                    .name("autototem.threshold")
                    .defaultValue(14)
                    .range(0, 20)
                    .build();

    private static final IntSetting SWITCH_DELAY_MS =
            new IntSetting.Builder()
                    .name("autototem.switchdelay")
                    .defaultValue(0)
                    .range(0, 500)
                    .build();

    private static final BooleanSetting MISSING_ARMOR =
            new BooleanSetting.Builder().name("autototem.missingarmor").defaultValue(true).build();

    private static final BooleanSetting SAFETY_ENABLED =
            new BooleanSetting.Builder().name("autototem.safety").defaultValue(true).build();

    private static final IntSetting SAFE_THRESHOLD =
            new IntSetting.Builder()
                    .name("autototem.safethreshold")
                    .defaultValue(10)
                    .range(0, 20)
                    .build();

    private static final BooleanSetting SUBTRACT_CALCULATED_DAMAGE =
            new BooleanSetting.Builder()
                    .name("autototem.subtractdamage")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting PREDICT_EXPLOSION_ENTITIES =
            new BooleanSetting.Builder()
                    .name("autototem.explosionentities")
                    .defaultValue(true)
                    .build();

    private static final BooleanSetting PREDICT_EXPLOSION_BLOCKS =
            new BooleanSetting.Builder()
                    .name("autototem.explosionblocks")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting PREDICT_FALL_DAMAGE =
            new BooleanSetting.Builder().name("autototem.falldamage").defaultValue(true).build();

    private static final BooleanSetting IGNORE_ELYTRA =
            new BooleanSetting.Builder()
                    .name("autototem.fallignoreelytra")
                    .defaultValue(false)
                    .build();

    private static BlockPos[] explosionSphere;

    private static boolean dangerEpisodeHandled;
    private static boolean offhandTotemObserved;
    private static int sourceMenuSlot = -1;
    private static long nextActionAtMs;
    private static InventoryClickStep inventoryClickStep = InventoryClickStep.NONE;
    private static boolean inventoryOpenedByModule;
    private static boolean transactionOffhandEmpty;

    private enum InventoryClickStep {
        NONE,
        EQUIP_PICKUP_TOTEM,
        EQUIP_PLACE_OFFHAND,
        EQUIP_RETURN_OLD_ITEM
    }

    private AutoTotem() {}

    public static void init() {
        // AutoTotem is edge-triggered and never restores the previous offhand.

        EventBus.TICK.register(
                "AutoTotem.tick",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private static void tick(Minecraft client) {
        if (!coreReady(client)) {
            closeModuleInventory(client);
            resetState();
            return;
        }

        // Once a pickup sequence starts, finish it even if the module is
        // toggled off mid-swap. Leaving an item on the carried cursor is worse
        // than completing the already-authorized inventory action.
        if (inventoryClickStep != InventoryClickStep.NONE) {
            tickInventoryClicks(client);
            return;
        }

        if (!ENABLED.get()) {
            resetState();
            return;
        }

        if (!inventoryScreenAvailable(client)) {
            return;
        }

        boolean danger = healthBelowThreshold(client);
        boolean offhandHasTotem =
                client.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
        boolean equippedTotemWasRemoved = offhandTotemObserved && !offhandHasTotem;
        offhandTotemObserved = offhandHasTotem;

        if (!danger) {
            // A safe interval rearms the module for the next distinct danger
            // episode. The current offhand item is intentionally left alone.
            dangerEpisodeHandled = false;
            return;
        }

        // A totem that was present and is now gone (usually after activation)
        // exposes the player to lethal damage again, so allow one replacement.
        if (equippedTotemWasRemoved) {
            dangerEpisodeHandled = false;
        }

        if (dangerEpisodeHandled) {
            return;
        }

        if (offhandHasTotem) {
            dangerEpisodeHandled = true;
            offhandTotemObserved = true;
            return;
        }

        if (System.currentTimeMillis() < nextActionAtMs) {
            return;
        }

        equipTotem(client);
    }

    private static void equipTotem(Minecraft client) {
        int containerId = client.player.inventoryMenu.containerId;
        int hotbarSlot = findTotemHotbarSlot(client);

        if (hotbarSlot != -1) {
            int menuSlot = Inventory.INVENTORY_SIZE + hotbarSlot;
            client.gameMode.handleContainerInput(
                    containerId, menuSlot, 40, ContainerInput.SWAP, client.player);
            dangerEpisodeHandled = true;
            nextActionAtMs = System.currentTimeMillis() + SWITCH_DELAY_MS.get();
            return;
        }

        int inventorySlot = findTotemInventorySlot(client);
        if (inventorySlot == -1) {
            nextActionAtMs =
                    System.currentTimeMillis() + Math.max(SWITCH_DELAY_MS.get(), RETRY_DELAY_MS);
            return;
        }

        sourceMenuSlot = inventorySlot;
        transactionOffhandEmpty = client.player.getOffhandItem().isEmpty();
        beginInventoryClicks(client, InventoryClickStep.EQUIP_PICKUP_TOTEM);
    }

    private static void beginInventoryClicks(Minecraft client, InventoryClickStep firstStep) {
        if (MinecraftClientAccess.screen(client) == null) {
            MinecraftClientAccess.setScreen(client, new InventoryScreen(client.player));
            inventoryOpenedByModule = true;
        } else if (MinecraftClientAccess.screen(client) instanceof InventoryScreen) {
            inventoryOpenedByModule = false;
        } else {
            return;
        }

        inventoryClickStep = firstStep;
        nextActionAtMs = System.currentTimeMillis() + SWITCH_DELAY_MS.get();
    }

    private static void tickInventoryClicks(Minecraft client) {
        if (!(MinecraftClientAccess.screen(client) instanceof InventoryScreen)) {
            if (MinecraftClientAccess.screen(client) != null) {
                // Do not overwrite chat, pause menus, or another container.
                return;
            }
            MinecraftClientAccess.setScreen(client, new InventoryScreen(client.player));
            inventoryOpenedByModule = true;
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionAtMs) {
            return;
        }

        switch (inventoryClickStep) {
            case EQUIP_PICKUP_TOTEM -> {
                if (sourceMenuSlot < 0
                        || client.player.inventoryMenu.getSlot(sourceMenuSlot).getItem().getItem()
                                != Items.TOTEM_OF_UNDYING) {
                    abortInventoryClicks(client);
                    return;
                }
                clickInventorySlot(client, sourceMenuSlot);
                inventoryClickStep = InventoryClickStep.EQUIP_PLACE_OFFHAND;
            }
            case EQUIP_PLACE_OFFHAND -> {
                clickInventorySlot(client, InventoryMenu.SHIELD_SLOT);
                if (transactionOffhandEmpty) {
                    finishTotemEquip(client);
                    return;
                }
                inventoryClickStep = InventoryClickStep.EQUIP_RETURN_OLD_ITEM;
            }
            case EQUIP_RETURN_OLD_ITEM -> {
                clickInventorySlot(client, sourceMenuSlot);
                finishTotemEquip(client);
                return;
            }
            case NONE -> {
                return;
            }
        }

        // Even with switchDelay=0, only one click is sent per client tick.
        nextActionAtMs = now + SWITCH_DELAY_MS.get();
    }

    private static void clickInventorySlot(Minecraft client, int slot) {
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                slot,
                0,
                ContainerInput.PICKUP,
                client.player);
    }

    private static void finishTotemEquip(Minecraft client) {
        inventoryClickStep = InventoryClickStep.NONE;
        dangerEpisodeHandled = true;
        sourceMenuSlot = -1;
        transactionOffhandEmpty = false;
        nextActionAtMs = System.currentTimeMillis() + SWITCH_DELAY_MS.get();
        closeModuleInventory(client);
    }

    private static void abortInventoryClicks(Minecraft client) {
        inventoryClickStep = InventoryClickStep.NONE;
        closeModuleInventory(client);
        resetState();
        nextActionAtMs =
                System.currentTimeMillis() + Math.max(SWITCH_DELAY_MS.get(), RETRY_DELAY_MS);
    }

    private static void closeModuleInventory(Minecraft client) {
        if (inventoryOpenedByModule
                && MinecraftClientAccess.screen(client) instanceof InventoryScreen) {
            MinecraftClientAccess.setScreen(client, null);
        }
        inventoryOpenedByModule = false;
    }

    private static int findTotemHotbarSlot(Minecraft client) {
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getItem(slot).getItem() == Items.TOTEM_OF_UNDYING) {
                return slot;
            }
        }
        return -1;
    }

    private static int findTotemInventorySlot(Minecraft client) {
        for (int slot = 9; slot < 36; slot++) {
            if (client.player.getInventory().getItem(slot).getItem() == Items.TOTEM_OF_UNDYING) {
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

    /**
     * LiquidBounce Totem.Health.healthBelowThreshold(): true when the player is
     * in enough danger to warrant a totem.
     */
    private static boolean healthBelowThreshold(Minecraft client) {
        if (MISSING_ARMOR.get() && hasMissingArmor(client)) {
            return true;
        }

        float health = playerHealth(client);
        boolean safetyOperating = SAFETY_ENABLED.get() && (isBurrowed(client) || isInHole(client));
        float allowedDamage =
                health - (safetyOperating ? SAFE_THRESHOLD.get() : HEALTH_THRESHOLD.get());

        if (allowedDamage <= 0.0F) {
            return true;
        }

        if (!SUBTRACT_CALCULATED_DAMAGE.get()) {
            allowedDamage = health;
        }

        float calculatedDamage = explosionDamageFromEntities(client, allowedDamage);
        if (calculatedDamage >= allowedDamage) {
            return true;
        }

        calculatedDamage =
                Math.max(calculatedDamage, explosionDamageFromBlocks(client, allowedDamage));
        if (calculatedDamage >= allowedDamage) {
            return true;
        }

        calculatedDamage += fallDamage(client);
        return calculatedDamage >= allowedDamage;
    }

    private static boolean hasMissingArmor(Minecraft client) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (client.player.getItemBySlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBurrowed(Minecraft client) {
        return isBlastResistant(client, feetBlockPos(client));
    }

    private static boolean isInHole(Minecraft client) {
        BlockPos feet = feetBlockPos(client);
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
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

    private static float explosionDamageFromEntities(Minecraft client, float allowedDamage) {
        if (!PREDICT_EXPLOSION_ENTITIES.get()) {
            return 0.0F;
        }

        float maxDamage = 0.0F;
        for (Entity entity : client.level.entitiesForRendering()) {
            maxDamage = Math.max(maxDamage, explosionDamageFromEntity(client, entity));
            if (maxDamage >= allowedDamage) {
                return maxDamage;
            }
        }
        return maxDamage;
    }

    private static float explosionDamageFromEntity(Minecraft client, Entity entity) {
        if (entity instanceof EndCrystal) {
            return explosionDamage(
                    client,
                    entity.position(),
                    6.0F,
                    12.0F,
                    144.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        if (entity instanceof PrimedTnt) {
            return explosionDamage(
                    client,
                    entity.position().add(0.0D, 0.0625D, 0.0D),
                    4.0F,
                    8.0F,
                    64.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        if (entity instanceof MinecartTNT) {
            return explosionDamage(
                    client,
                    entity.position(),
                    4.0F,
                    8.0F,
                    64.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        if (entity instanceof Creeper creeper) {
            float power = (creeper.isPowered() ? 2.0F : 1.0F) * 3.0F;
            return explosionDamage(
                    client,
                    entity.position(),
                    power,
                    power * 2.0F,
                    power * power * 4.0F,
                    Explosion.getDefaultDamageSource(client.level, entity));
        }

        return 0.0F;
    }

    /**
     * Mirrors the vanilla entity damage formula for explosions.
     */
    private static float explosionDamage(
            Minecraft client,
            Vec3 pos,
            float power,
            float explosionRange,
            float damageDistance,
            DamageSource source) {
        Player player = client.player;
        if (player == null || player.distanceToSqr(pos) > damageDistance) {
            return 0.0F;
        }

        float exposure = ServerExplosion.getSeenPercent(pos, player);
        double distanceDecay = 1.0D - Math.sqrt(player.distanceToSqr(pos)) / explosionRange;
        double pre = exposure * distanceDecay;
        double damage = (pre * pre + pre) / 2.0D * 7.0D * explosionRange + 1.0D;
        if (damage == 0.0D) {
            return 0.0F;
        }

        return effectiveDamage(player, source, (float) damage);
    }

    private static float explosionDamageFromBlocks(Minecraft client, float allowedDamage) {
        if (!PREDICT_EXPLOSION_BLOCKS.get()) {
            return 0.0F;
        }

        BlockPos[] sphere = explosionSphere();
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
                    explosionDamage(
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

    private static float fallDamage(Minecraft client) {
        Player player = client.player;
        if (player == null || !PREDICT_FALL_DAMAGE.get() || player.fallDistance <= 3.0F) {
            return 0.0F;
        }

        if (IGNORE_ELYTRA.get() && player.isFallFlying() && player.hasPose(Pose.FALL_FLYING)) {
            return 0.0F;
        }

        BlockPos landing = predictLandingBlock(player);
        float multiplier = fallDamageMultiplier(client, landing);
        if (multiplier <= 0.0F) {
            return 0.0F;
        }

        int damage = Math.max(0, Mth.ceil((player.fallDistance - 3.0F) * multiplier));
        if (damage <= 0) {
            return 0.0F;
        }

        return effectiveDamage(player, player.damageSources().fall(), damage);
    }

    private static BlockPos predictLandingBlock(Player player) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        double vx = player.getDeltaMovement().x;
        double vy = player.getDeltaMovement().y;
        double vz = player.getDeltaMovement().z;
        double landY = Math.floor(y - 0.01D) + 1.0D;

        for (int tick = 0; tick < 20; tick++) {
            vy = (vy - 0.08D) * 0.98D;
            y += vy;
            vx *= 0.91D;
            vz *= 0.91D;
            x += vx;
            z += vz;

            if (y <= landY) {
                return new BlockPos(Mth.floor(x), Mth.floor(landY - 0.01D), Mth.floor(z));
            }
        }

        return null;
    }

    private static float fallDamageMultiplier(Minecraft client, BlockPos pos) {
        if (pos == null) {
            return 1.0F;
        }

        Block block = client.level.getBlockState(pos).getBlock();
        if (block == Blocks.WATER || block == Blocks.COBWEB || block == Blocks.POWDER_SNOW) {
            return 0.0F;
        }
        if (block == Blocks.HAY_BLOCK || block == Blocks.HONEY_BLOCK) {
            return 0.2F;
        }
        if (block == Blocks.SLIME_BLOCK) {
            return 1.0F;
        }
        if (block instanceof BedBlock) {
            return 0.5F;
        }
        return 1.0F;
    }

    /**
     * Armor reduction mirroring vanilla's damage pipeline; magic (protection)
     * enchantments are not included in the prediction.
     */
    private static float effectiveDamage(Player player, DamageSource source, float damage) {
        if (player.isDeadOrDying() || player.getAbilities().invulnerable) {
            return 0.0F;
        }

        float armor = player.getArmorValue();
        float toughness = (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float armorPoints =
                Math.min(20.0F, Math.max(armor / 5.0F, armor - damage / (2.0F + toughness / 4.0F)));
        return Math.max(0.0F, damage * (1.0F - armorPoints / 25.0F));
    }

    private static BlockPos[] explosionSphere() {
        if (explosionSphere != null) {
            return explosionSphere;
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
        explosionSphere = offsets.toArray(new BlockPos[0]);
        return explosionSphere;
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

    private static void resetState() {
        dangerEpisodeHandled = false;
        offhandTotemObserved = false;
        sourceMenuSlot = -1;
        inventoryClickStep = InventoryClickStep.NONE;
        inventoryOpenedByModule = false;
        transactionOffhandEmpty = false;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoTotem: "
                        + statusText()
                        + ", threshold: "
                        + HEALTH_THRESHOLD.get()
                        + ", safeThreshold: "
                        + SAFE_THRESHOLD.get()
                        + ", missingArmor: "
                        + toggleText(MISSING_ARMOR.get())
                        + ", safety: "
                        + toggleText(SAFETY_ENABLED.get())
                        + ", subtractDamage: "
                        + toggleText(SUBTRACT_CALCULATED_DAMAGE.get())
                        + ", explosionEntities: "
                        + toggleText(PREDICT_EXPLOSION_ENTITIES.get())
                        + ", explosionBlocks: "
                        + toggleText(PREDICT_EXPLOSION_BLOCKS.get())
                        + ", fallDamage: "
                        + toggleText(PREDICT_FALL_DAMAGE.get())
                        + ", oneShot: enabled, keepOffhand: enabled"
                        + ". Usage: .moons autototem <enable|disable|threshold 0-20|safethreshold 0-20"
                        + "|missingarmor enable|disable|safety enable|disable|subtractdamage enable|disable"
                        + "|explosionentities enable|disable|explosionblocks enable|disable"
                        + "|falldamage enable|disable|fallignoreelytra enable|disable"
                        + "|switchdelay ms>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        if (inventoryClickStep == InventoryClickStep.NONE) {
            closeModuleInventory(client);
            resetState();
            nextActionAtMs = 0L;
        }
        ClientChat.send(client, "AutoTotem " + statusText() + ".");
        return 1;
    }

    public static int setThreshold(Minecraft client, int value) {
        HEALTH_THRESHOLD.set(value);
        ClientChat.send(client, "AutoTotem threshold set to " + HEALTH_THRESHOLD.get() + ".");
        return 1;
    }

    public static int setSafeThreshold(Minecraft client, int value) {
        SAFE_THRESHOLD.set(value);
        ClientChat.send(client, "AutoTotem safeThreshold set to " + SAFE_THRESHOLD.get() + ".");
        return 1;
    }

    public static int setMissingArmor(Minecraft client, boolean value) {
        MISSING_ARMOR.set(value);
        ClientChat.send(client, "AutoTotem missingArmor " + toggleText(MISSING_ARMOR.get()) + ".");
        return 1;
    }

    public static int setSafety(Minecraft client, boolean value) {
        SAFETY_ENABLED.set(value);
        ClientChat.send(client, "AutoTotem safety " + toggleText(SAFETY_ENABLED.get()) + ".");
        return 1;
    }

    public static int setSubtractDamage(Minecraft client, boolean value) {
        SUBTRACT_CALCULATED_DAMAGE.set(value);
        ClientChat.send(
                client,
                "AutoTotem subtractDamage " + toggleText(SUBTRACT_CALCULATED_DAMAGE.get()) + ".");
        return 1;
    }

    public static int setExplosionEntities(Minecraft client, boolean value) {
        PREDICT_EXPLOSION_ENTITIES.set(value);
        ClientChat.send(
                client,
                "AutoTotem explosionEntities "
                        + toggleText(PREDICT_EXPLOSION_ENTITIES.get())
                        + ".");
        return 1;
    }

    public static int setExplosionBlocks(Minecraft client, boolean value) {
        PREDICT_EXPLOSION_BLOCKS.set(value);
        ClientChat.send(
                client,
                "AutoTotem explosionBlocks " + toggleText(PREDICT_EXPLOSION_BLOCKS.get()) + ".");
        return 1;
    }

    public static int setFallDamage(Minecraft client, boolean value) {
        PREDICT_FALL_DAMAGE.set(value);
        ClientChat.send(
                client, "AutoTotem fallDamage " + toggleText(PREDICT_FALL_DAMAGE.get()) + ".");
        return 1;
    }

    public static int setIgnoreElytra(Minecraft client, boolean value) {
        IGNORE_ELYTRA.set(value);
        ClientChat.send(client, "AutoTotem ignoreElytra " + toggleText(IGNORE_ELYTRA.get()) + ".");
        return 1;
    }

    public static int setSwitchDelay(Minecraft client, int value) {
        SWITCH_DELAY_MS.set(value);
        ClientChat.send(client, "AutoTotem switchDelay set to " + SWITCH_DELAY_MS.get() + "ms.");
        return 1;
    }

    private static String toggleText(boolean value) {
        return value ? "enabled" : "disabled";
    }
}
