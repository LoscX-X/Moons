/*
 * AutoTool for Moons.
 *
 * While mining a block the
 * best hotbar tool is selected and kept selected after mining stops.
 */
package com.blanoir.moons.client.module.impl.world;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.world.LegacyWorld;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.List;
import java.util.Set;

public final class AutoTool {
    private static final Set<Block> SILK_TOUCH_BLOCKS =
            Set.of(Blocks.ender_chest, Blocks.glowstone, Blocks.sea_lantern);

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autotool.enabled").defaultValue(false).build();

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("autotool.mode")
                    .defaultValue(Mode.DYNAMIC)
                    .option(Mode.DYNAMIC, "dynamic")
                    .option(Mode.STATIC, "static")
                    .build();

    private static final IntSetting STATIC_SLOT =
            new IntSetting.Builder().name("autotool.slot").defaultValue(0).range(0, 8).build();

    private static final BooleanSetting IGNORE_DURABILITY =
            new BooleanSetting.Builder()
                    .name("autotool.ignoredurability")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting REQUIRE_SNEAKING =
            new BooleanSetting.Builder().name("autotool.sneaking").defaultValue(false).build();

    private static final BooleanSetting NOT_DURING_COMBAT =
            new BooleanSetting.Builder().name("autotool.combat").defaultValue(false).build();

    private static final IntSetting COMBAT_GRACE_TICKS =
            new IntSetting.Builder()
                    .name("autotool.combatgrace")
                    .defaultValue(30)
                    .range(0, 100)
                    .build();

    private static final BooleanSetting SILK_TOUCH =
            new BooleanSetting.Builder().name("autotool.silktouch").defaultValue(false).build();

    private static int combatLockTicks;
    private static int lastPlayerHurtTime;
    private static boolean manualOverride;
    private static final HotbarLease HOTBAR =
            new HotbarLease("AutoTool", HotbarLease.PRIORITY_TOOL);

    private AutoTool() {}

    public static void init() {

        EventBus.TICK.register(
                "AutoTool.tick",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get() || !ClientReady.gameplay(client)) {
            HOTBAR.release(client);
            manualOverride = false;
            combatLockTicks = 0;
            lastPlayerHurtTime = 0;
            return;
        }

        if (PlacementCoordinator.busy(PlacementCoordinator.Owner.ANTI_WEB)) {
            HOTBAR.release(client);
            return;
        }

        if (HOTBAR.active() && client.thePlayer.inventory.currentItem != HOTBAR.leasedSlot()) {
            HOTBAR.abandon();
            manualOverride = true;
        }

        updateCombatLock(client);
        if (NOT_DURING_COMBAT.get() && isInCombat(client)) {
            // Combat only prevents a new automatic tool selection. The module
            // never takes ownership of the old slot and therefore never swaps back.
            return;
        }

        IBlockState state = miningBlockState(client);
        if (state != null) {
            if (manualOverride) return;
            int best = findBestToolSlot(client, state);
            if (best == -1 || best == client.thePlayer.inventory.currentItem) {
                return;
            }

            HOTBAR.acquire(client, best);
        } else {
            HOTBAR.release(client);
            manualOverride = false;
        }
    }

    private static IBlockState miningBlockState(Minecraft client) {
        MovingObjectPosition hit = client.objectMouseOver;
        if (hit == null
                || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !client.gameSettings.keyBindAttack.isKeyDown()) {
            return null;
        }

        if (REQUIRE_SNEAKING.get() && !client.thePlayer.isSneaking()) {
            return null;
        }

        IBlockState state = client.theWorld.getBlockState(hit.getBlockPos());
        if (LegacyWorld.air(state)
                || state.getBlock().getBlockHardness(client.theWorld, hit.getBlockPos()) < 0.0F) {
            return null;
        }

        double range = Minecraft.getMinecraft().playerController.getBlockReachDistance();
        return hit.hitVec.squareDistanceTo(client.thePlayer.getPositionEyes(1.0F)) <= range * range
                ? state
                : null;
    }

    private static int findBestToolSlot(Minecraft client, IBlockState state) {
        if (MODE.get() == Mode.STATIC) {
            int slot = STATIC_SLOT.get();
            return LegacyItems.empty(client.thePlayer.inventory.getStackInSlot(slot)) ? -1 : slot;
        }

        if (client.thePlayer.capabilities.isCreativeMode) {
            return -1;
        }

        InventoryPlayer inventory = client.thePlayer.inventory;
        int selected = inventory.currentItem;
        boolean silkRequired = SILK_TOUCH.get() && SILK_TOUCH_BLOCKS.contains(state.getBlock());
        boolean cobweb = (state.getBlock() == Blocks.web);
        int best = -1;
        int bestCobwebPriority = Integer.MIN_VALUE;
        float bestSpeed = -1.0F;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (LegacyItems.empty(stack)) {
                continue;
            }

            if (!IGNORE_DURABILITY.get()
                    && stack.getMaxDamage() > 0
                    && stack.getMaxDamage() - stack.getItemDamage() <= 2) {
                continue;
            }

            if (silkRequired && enchantmentLevel(client, stack, Enchantment.silkTouch) == 0) {
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
                                            && (best == -1
                                                    || hotbarDistance(slot, selected)
                                                            < hotbarDistance(best, selected)))))) {
                best = slot;
                bestCobwebPriority = cobwebPriority;
                bestSpeed = speed;
            }
        }

        return best;
    }

    private static int cobwebPriority(ItemStack stack) {
        if (LegacyItems.is(stack, Items.shears)) {
            return 2;
        }
        if (stack.getItem() instanceof net.minecraft.item.ItemSword) {
            return 1;
        }
        return 0;
    }

    private static float destroySpeedWithEnchantment(
            Minecraft client, ItemStack stack, IBlockState state) {
        float speed = stack.getStrVsBlock(state.getBlock());
        int efficiency = enchantmentLevel(client, stack, Enchantment.efficiency);

        if (speed > 1.0F && efficiency > 0) {
            speed += Math.min(1024.0F, efficiency * efficiency + 1.0F);
        }

        return speed;
    }

    private static int enchantmentLevel(
            Minecraft client, ItemStack stack, Enchantment enchantment) {
        return net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                enchantment.effectId, stack);
    }

    private static int hotbarDistance(int slot, int selected) {
        return Math.abs(slot - selected);
    }

    private static void updateCombatLock(Minecraft client) {
        if (combatLockTicks > 0) {
            combatLockTicks--;
        }

        int hurtTime = client.thePlayer.hurtTime;
        boolean newlyHurt = hurtTime > lastPlayerHurtTime;
        lastPlayerHurtTime = hurtTime;
        if (newlyHurt
                && client.thePlayer.getAITarget() instanceof EntityPlayer attacker
                && Targeting.isValidTargetPlayer(client, attacker)) {
            lockCombat();
        }
    }

    private static boolean isInCombat(Minecraft client) {
        return combatLockTicks > 0 || hasImmediateThreat(client);
    }

    private static boolean hasImmediateThreat(Minecraft client) {
        MovingObjectPosition hit = client.objectMouseOver;
        if (hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && Targeting.isEnemyPlayer(client, hit.entityHit)
                && Targeting.isWithinInteractionRange(client, hit.entityHit)) {
            return true;
        }

        for (EntityPlayer target : client.theWorld.playerEntities) {
            if (!Targeting.isValidTargetPlayerWithinRange(client, target, 5.5D)
                    || !client.thePlayer.canEntityBeSeen(target)) {
                continue;
            }

            double distance = client.thePlayer.getDistanceToEntity(target);
            if (distance <= 3.4D) {
                return true;
            }

            Vec3 towardPlayer =
                    VecMath.position(client.thePlayer).subtract(VecMath.position(target));
            Vec3 horizontalDirection = new Vec3(towardPlayer.xCoord, 0.0D, towardPlayer.zCoord);
            double directionLength = horizontalDirection.lengthVector();
            if (directionLength < 1.0E-5D) {
                return true;
            }
            horizontalDirection = VecMath.scale(horizontalDirection, 1.0D / directionLength);

            Vec3 look = target.getLookVec();
            Vec3 horizontalLook = new Vec3(look.xCoord, 0.0D, look.zCoord);
            double lookLength = horizontalLook.lengthVector();
            if (lookLength < 1.0E-5D
                    || VecMath.scale(horizontalLook, 1.0D / lookLength)
                                    .dotProduct(horizontalDirection)
                            < 0.35D) {
                continue;
            }

            Vec3 targetVelocity = VecMath.motion(target);
            Vec3 playerVelocity = VecMath.motion(client.thePlayer);
            Vec3 relativeVelocity =
                    new Vec3(
                            targetVelocity.xCoord - playerVelocity.xCoord,
                            0.0D,
                            targetVelocity.zCoord - playerVelocity.zCoord);
            if (relativeVelocity.dotProduct(horizontalDirection) >= 0.025D) {
                return true;
            }
        }
        return false;
    }

    public static void onAttack(Entity target) {
        Minecraft client = Minecraft.getMinecraft();
        if (ENABLED.get() && NOT_DURING_COMBAT.get() && Targeting.isEnemyPlayer(client, target)) {
            lockCombat();
        }
    }

    private static void lockCombat() {
        combatLockTicks = Math.max(combatLockTicks, COMBAT_GRACE_TICKS.get());
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoTool: "
                        + statusText()
                        + ", mode: "
                        + MODE.serialized()
                        + ", slot: "
                        + STATIC_SLOT.get()
                        + ", ignoreDurability: "
                        + toggleText(IGNORE_DURABILITY.get())
                        + ", silkTouch: "
                        + toggleText(SILK_TOUCH.get())
                        + ", sneaking: "
                        + toggleText(REQUIRE_SNEAKING.get())
                        + ", combat: "
                        + toggleText(NOT_DURING_COMBAT.get())
                        + ", combatGrace: "
                        + COMBAT_GRACE_TICKS.get()
                        + "t"
                        + ", keepSelected: enabled, cobweb: shears > sword"
                        + ". Usage: .moons autotool <enable|disable|mode dynamic|static|slot 0-8|ignoredurability enable|disable|silktouch enable|disable|sneaking enable|disable|combat enable|disable|combatdelay 0-100>");
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
        ClientChat.send(
                client, "AutoTool ignoreDurability " + toggleText(IGNORE_DURABILITY.get()) + ".");
        return 1;
    }

    public static int setSilkTouch(Minecraft client, boolean value) {
        SILK_TOUCH.set(value);
        ClientChat.send(client, "AutoTool silkTouch " + toggleText(SILK_TOUCH.get()) + ".");
        return 1;
    }

    public static int setRequireSneaking(Minecraft client, boolean value) {
        REQUIRE_SNEAKING.set(value);
        ClientChat.send(
                client, "AutoTool requireSneaking " + toggleText(REQUIRE_SNEAKING.get()) + ".");
        return 1;
    }

    public static int setNotDuringCombat(Minecraft client, boolean value) {
        NOT_DURING_COMBAT.set(value);
        ClientChat.send(
                client, "AutoTool notDuringCombat " + toggleText(NOT_DURING_COMBAT.get()) + ".");
        return 1;
    }

    public static int setCombatGraceTicks(Minecraft client, int value) {
        COMBAT_GRACE_TICKS.set(value);
        ClientChat.send(
                client, "AutoTool combat delay set to " + COMBAT_GRACE_TICKS.get() + " ticks.");
        return 1;
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static boolean staticMode() {
        return MODE.get() == Mode.STATIC;
    }

    private static String toggleText(boolean value) {
        return value ? "enabled" : "disabled";
    }

    private enum Mode {
        DYNAMIC,
        STATIC
    }
}
