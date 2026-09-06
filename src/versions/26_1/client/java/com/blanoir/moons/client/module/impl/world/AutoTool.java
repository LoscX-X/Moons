/*
 * AutoTool for Moons.
 *
 * Ported from LiquidBounce (GPL-3.0) ModuleAutoTool: while mining a block the
 * best hotbar tool is selected and kept selected after mining stops.
 */
package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.impl.player.AntiWeb;
import com.blanoir.moons.client.management.inventory.HotbarLease;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.management.targeting.Targeting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

public final class AutoTool {
    private static final Set<Block> SILK_TOUCH_BLOCKS = Set.of(
            Blocks.ENDER_CHEST,
            Blocks.GLOWSTONE,
            Blocks.SEA_LANTERN,
            Blocks.TURTLE_EGG
    );

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("autotool.enabled")
                    .defaultValue(false)
                    .build();

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("autotool.mode")
                    .defaultValue(Mode.DYNAMIC)
                    .option(Mode.DYNAMIC, "dynamic")
                    .option(Mode.STATIC, "static")
                    .build();

    private static final IntSetting STATIC_SLOT =
            new IntSetting.Builder()
                    .name("autotool.slot")
                    .defaultValue(0)
                    .range(0, 8)
                    .build();

    private static final BooleanSetting IGNORE_DURABILITY =
            new BooleanSetting.Builder()
                    .name("autotool.ignoredurability")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting REQUIRE_SNEAKING =
            new BooleanSetting.Builder()
                    .name("autotool.sneaking")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting NOT_DURING_COMBAT =
            new BooleanSetting.Builder()
                    .name("autotool.combat")
                    .defaultValue(false)
                    .build();

    private static final IntSetting COMBAT_GRACE_TICKS =
            new IntSetting.Builder()
                    .name("autotool.combatgrace")
                    .defaultValue(30)
                    .range(0, 100)
                    .build();

    private static final BooleanSetting SILK_TOUCH =
            new BooleanSetting.Builder()
                    .name("autotool.silktouch")
                    .defaultValue(false)
                    .build();

    private static int combatLockTicks;
    private static int lastPlayerHurtTime;
    private static boolean manualOverride;
    private static final HotbarLease HOTBAR = new HotbarLease("AutoTool", 10);

    private AutoTool() {
    }

    public static void init() {

        EventBus.TICK.register("AutoTool.tick", event -> {
            Minecraft client = event.client();
            tick(client);
        });
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get() || !ready(client)) {
            HOTBAR.release(client);
            manualOverride = false;
            combatLockTicks = 0;
            lastPlayerHurtTime = 0;
            return;
        }

        if (AntiWeb.isBusy()) {
            HOTBAR.release(client);
            return;
        }

        if (HOTBAR.active() && client.player.getInventory().getSelectedSlot() != HOTBAR.leasedSlot()) {
            HOTBAR.abandon();
            manualOverride = true;
        }

        updateCombatLock(client);
        if (NOT_DURING_COMBAT.get() && isInCombat(client)) {
            // Combat only prevents a new automatic tool selection. The module
            // never takes ownership of the old slot and therefore never swaps back.
            return;
        }

        if (isMiningBlock(client)) {
            if (manualOverride) return;
            BlockState state = client.level.getBlockState(((BlockHitResult) client.hitResult).getBlockPos());
            int best = findBestToolSlot(client, state);
            if (best == -1 || best == client.player.getInventory().getSelectedSlot()) {
                return;
            }

            HOTBAR.acquire(client, best);
        } else {
            HOTBAR.release(client);
            manualOverride = false;
        }
    }

    private static boolean isMiningBlock(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult hit)
                || !client.options.keyAttack.isDown()) {
            return false;
        }

        if (REQUIRE_SNEAKING.get() && !client.player.isShiftKeyDown()) {
            return false;
        }

        BlockState state = client.level.getBlockState(hit.getBlockPos());
        if (state.isAir() || state.getDestroySpeed(client.level, hit.getBlockPos()) < 0.0F) {
            return false;
        }

        double range = client.player.blockInteractionRange();
        return hit.getLocation().distanceToSqr(client.player.getEyePosition()) <= range * range;
    }

    private static int findBestToolSlot(Minecraft client, BlockState state) {
        if (MODE.get() == Mode.STATIC) {
            int slot = STATIC_SLOT.get();
            return client.player.getInventory().getItem(slot).isEmpty() ? -1 : slot;
        }

        if (client.player.getAbilities().instabuild) {
            return -1;
        }

        Inventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        boolean silkRequired = SILK_TOUCH.get() && SILK_TOUCH_BLOCKS.contains(state.getBlock());
        boolean cobweb = state.is(Blocks.COBWEB);
        int best = -1;
        int bestCobwebPriority = Integer.MIN_VALUE;
        float bestSpeed = -1.0F;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (!IGNORE_DURABILITY.get()
                    && stack.getMaxDamage() > 0
                    && stack.getMaxDamage() - stack.getDamageValue() <= 2) {
                continue;
            }

            if (silkRequired && enchantmentLevel(client, stack, Enchantments.SILK_TOUCH) == 0) {
                continue;
            }

            float speed = destroySpeedWithEnchantment(client, stack, state);
            if (speed <= 0.0F) {
                continue;
            }

            int cobwebPriority = cobweb ? cobwebPriority(stack) : 0;
            if (cobwebPriority > bestCobwebPriority
                    || (cobwebPriority == bestCobwebPriority
                    && (speed > bestSpeed + 1.0E-4F
                    || (Math.abs(speed - bestSpeed) <= 1.0E-4F
                    && (best == -1 || hotbarDistance(slot, selected) < hotbarDistance(best, selected)))))) {
                best = slot;
                bestCobwebPriority = cobwebPriority;
                bestSpeed = speed;
            }
        }

        return best;
    }

    private static int cobwebPriority(ItemStack stack) {
        if (stack.is(Items.SHEARS)) {
            return 2;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return 1;
        }
        return 0;
    }

    private static float destroySpeedWithEnchantment(Minecraft client, ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        int efficiency = enchantmentLevel(client, stack, Enchantments.EFFICIENCY);

        if (speed > 1.0F && efficiency > 0) {
            speed += Math.min(1024.0F, efficiency * efficiency + 1.0F);
        }

        return speed;
    }

    private static int enchantmentLevel(
            Minecraft client,
            ItemStack stack,
            ResourceKey<Enchantment> enchantment
    ) {
        var currentLevel = client == null ? null : client.level;
        if (currentLevel == null) {
            return 0;
        }

        Holder<Enchantment> holder = currentLevel.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(enchantment)
                .orElse(null);
        return holder == null ? 0 : stack.getEnchantments().getLevel(holder);
    }

    private static int hotbarDistance(int slot, int selected) {
        return Math.abs(slot - selected);
    }

    private static void updateCombatLock(Minecraft client) {
        if (combatLockTicks > 0) {
            combatLockTicks--;
        }

        int hurtTime = client.player.hurtTime;
        boolean newlyHurt = hurtTime > lastPlayerHurtTime;
        lastPlayerHurtTime = hurtTime;
        if (newlyHurt
                && client.player.getLastHurtByMob() instanceof Player attacker
                && Targeting.isValidTargetPlayer(client, attacker)) {
            lockCombat();
        }
    }

    private static boolean isInCombat(Minecraft client) {
        return combatLockTicks > 0 || hasImmediateThreat(client);
    }

    private static boolean hasImmediateThreat(Minecraft client) {
        if (client.hitResult instanceof EntityHitResult hit
                && Targeting.isEnemyPlayer(client, hit.getEntity())
                && Targeting.isWithinInteractionRange(client, hit.getEntity())) {
            return true;
        }

        for (Player target : client.level.players()) {
            if (!Targeting.isValidTargetPlayerWithinRange(client, target, 5.5D)
                    || !client.player.hasLineOfSight(target)) {
                continue;
            }

            double distance = client.player.distanceTo(target);
            if (distance <= 3.4D) {
                return true;
            }

            Vec3 towardPlayer = client.player.position().subtract(target.position());
            Vec3 horizontalDirection = new Vec3(
                    towardPlayer.x, 0.0D, towardPlayer.z);
            double directionLength = horizontalDirection.length();
            if (directionLength < 1.0E-5D) {
                return true;
            }
            horizontalDirection = horizontalDirection.scale(1.0D / directionLength);

            Vec3 look = target.getLookAngle();
            Vec3 horizontalLook = new Vec3(look.x, 0.0D, look.z);
            double lookLength = horizontalLook.length();
            if (lookLength < 1.0E-5D
                    || horizontalLook.scale(1.0D / lookLength)
                    .dot(horizontalDirection) < 0.35D) {
                continue;
            }

            Vec3 targetVelocity = target.getDeltaMovement();
            Vec3 playerVelocity = client.player.getDeltaMovement();
            Vec3 relativeVelocity = new Vec3(
                    targetVelocity.x - playerVelocity.x,
                    0.0D,
                    targetVelocity.z - playerVelocity.z);
            if (relativeVelocity.dot(horizontalDirection) >= 0.025D) {
                return true;
            }
        }
        return false;
    }

    public static void onAttack(Entity target) {
        Minecraft client = Minecraft.getInstance();
        if (ENABLED.get() && NOT_DURING_COMBAT.get() && Targeting.isEnemyPlayer(client, target)) {
            lockCombat();
        }
    }

    private static void lockCombat() {
        combatLockTicks = Math.max(combatLockTicks, COMBAT_GRACE_TICKS.get());
    }

    private static boolean ready(Minecraft client) { return ClientReady.gameplay(client); }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoTool: " + statusText()
                        + ", mode: " + MODE.serialized()
                        + ", slot: " + STATIC_SLOT.get()
                        + ", ignoreDurability: " + toggleText(IGNORE_DURABILITY.get())
                        + ", silkTouch: " + toggleText(SILK_TOUCH.get())
                        + ", sneaking: " + toggleText(REQUIRE_SNEAKING.get())
                        + ", combat: " + toggleText(NOT_DURING_COMBAT.get())
                        + ", combatGrace: " + COMBAT_GRACE_TICKS.get() + "t"
                        + ", keepSelected: enabled, cobweb: shears > sword"
                        + ". Usage: .moons autotool <enable|disable|mode dynamic|static|slot 0-8|ignoredurability enable|disable|silktouch enable|disable|sneaking enable|disable|combat enable|disable|combatdelay 0-100>"
        );
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        if (!ENABLED.get()) {
            HOTBAR.release(client);
            manualOverride = false;
            combatLockTicks = 0;
            lastPlayerHurtTime = 0;
        }
        ClientChat.send(client, "AutoTool " + statusText() + ".");
        return 1;
    }

    public static int setMode(Minecraft client, String value) {
        MODE.deserialize(value);
        ClientChat.send(client, "AutoTool mode set to " + MODE.serialized() + ".");
        return 1;
    }

    public static int setStaticSlot(Minecraft client, int value) {
        STATIC_SLOT.set(value);
        ClientChat.send(client, "AutoTool slot set to " + STATIC_SLOT.get() + ".");
        return 1;
    }

    public static int setIgnoreDurability(Minecraft client, boolean value) {
        IGNORE_DURABILITY.set(value);
        ClientChat.send(client, "AutoTool ignoreDurability " + toggleText(IGNORE_DURABILITY.get()) + ".");
        return 1;
    }

    public static int setSilkTouch(Minecraft client, boolean value) {
        SILK_TOUCH.set(value);
        ClientChat.send(client, "AutoTool silkTouch " + toggleText(SILK_TOUCH.get()) + ".");
        return 1;
    }

    public static int setRequireSneaking(Minecraft client, boolean value) {
        REQUIRE_SNEAKING.set(value);
        ClientChat.send(client, "AutoTool requireSneaking " + toggleText(REQUIRE_SNEAKING.get()) + ".");
        return 1;
    }

    public static int setNotDuringCombat(Minecraft client, boolean value) {
        NOT_DURING_COMBAT.set(value);
        ClientChat.send(client, "AutoTool notDuringCombat " + toggleText(NOT_DURING_COMBAT.get()) + ".");
        return 1;
    }

    public static int setCombatGraceTicks(Minecraft client, int value) {
        COMBAT_GRACE_TICKS.set(value);
        ClientChat.send(client,
                "AutoTool combat delay set to " + COMBAT_GRACE_TICKS.get() + " ticks.");
        return 1;
    }

    public static List<String> modeOptions() { return MODE.optionIds(); }
    public static boolean staticMode() { return MODE.get() == Mode.STATIC; }

    private static String toggleText(boolean value) {
        return value ? "enabled" : "disabled";
    }

    private enum Mode { DYNAMIC, STATIC }
}
