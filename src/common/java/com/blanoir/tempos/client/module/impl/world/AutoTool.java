/*
 * AutoTool for Tempos.
 *
 * While mining a block the
 * best hotbar tool is selected and kept selected after mining stops.
 */
package com.blanoir.tempos.client.module.impl.world;


import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
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

import java.util.Set;

@ModuleInfo(
        category = ModuleCategory.WORLD,
        description = "Selects a hotbar tool for the block you are mining.")
public final class AutoTool extends ClientFeature {
    public static final AutoTool INSTANCE = new AutoTool();

    private final Set<Block> silkTouchBlocks =
            Set.of(Blocks.ENDER_CHEST, Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.TURTLE_EGG);

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("autotool.enabled").defaultValue(false).build();

    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("autotool.mode")
                    .defaultValue(Mode.DYNAMIC)
                    .option(Mode.DYNAMIC, "dynamic")
                    .option(Mode.STATIC, "static")
                    .build();

    private final IntSetting staticSlot =
            new IntSetting.Builder().name("autotool.slot").defaultValue(0).range(0, 8).build();

    private final BooleanSetting ignoreDurability =
            new BooleanSetting.Builder()
                    .name("autotool.ignoredurability")
                    .defaultValue(false)
                    .build();

    private final BooleanSetting requireSneaking =
            new BooleanSetting.Builder().name("autotool.sneaking").defaultValue(false).build();

    private final BooleanSetting notDuringCombat =
            new BooleanSetting.Builder().name("autotool.combat").defaultValue(false).build();

    private final IntSetting combatGraceTicks =
            new IntSetting.Builder()
                    .name("autotool.combatgrace")
                    .defaultValue(30)
                    .range(0, 100)
                    .build();

    private final BooleanSetting silkTouch =
            new BooleanSetting.Builder().name("autotool.silktouch").defaultValue(false).build();

    private int combatLockTicks;
    private int lastPlayerHurtTime;
    private boolean manualOverride;
    private final HotbarLease hotbar = new HotbarLease("AutoTool", HotbarLease.PRIORITY_TOOL);

    private AutoTool() {
        super("autotool", "AutoTool");
    }

    @Override
    public void registerEvents() {

        EventBus.TICK.register(
                "AutoTool.tick",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private void tick(Minecraft client) {
        if (!this.enabled.get() || !ClientReady.gameplay(client)) {
            this.hotbar.release(client);
            this.manualOverride = false;
            this.combatLockTicks = 0;
            this.lastPlayerHurtTime = 0;
            return;
        }

        if (PlacementCoordinator.busy(PlacementCoordinator.Owner.ANTI_WEB)) {
            this.hotbar.release(client);
            return;
        }

        if (this.hotbar.active()
                && client.player.getInventory().getSelectedSlot() != this.hotbar.leasedSlot()) {
            this.hotbar.abandon();
            this.manualOverride = true;
        }

        updateCombatLock(client);
        if (this.notDuringCombat.get() && isInCombat(client)) {
            // Combat only prevents a new automatic tool selection. The module
            // never takes ownership of the old slot and therefore never swaps back.
            return;
        }

        BlockState state = miningBlockState(client);
        if (state != null) {
            if (this.manualOverride) return;
            int best = findBestToolSlot(client, state);
            if (best == -1 || best == client.player.getInventory().getSelectedSlot()) {
                return;
            }

            this.hotbar.acquire(client, best);
        } else {
            this.hotbar.release(client);
            this.manualOverride = false;
        }
    }

    private BlockState miningBlockState(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult hit)
                || !client.options.keyAttack.isDown()) {
            return null;
        }

        if (this.requireSneaking.get() && !client.player.isShiftKeyDown()) {
            return null;
        }

        BlockState state = client.level.getBlockState(hit.getBlockPos());
        if (state.isAir() || state.getDestroySpeed(client.level, hit.getBlockPos()) < 0.0F) {
            return null;
        }

        double range = client.player.blockInteractionRange();
        return hit.getLocation().distanceToSqr(client.player.getEyePosition()) <= range * range
                ? state
                : null;
    }

    private int findBestToolSlot(Minecraft client, BlockState state) {
        if (this.mode.get() == Mode.STATIC) {
            int slot = this.staticSlot.get();
            return client.player.getInventory().getItem(slot).isEmpty() ? -1 : slot;
        }

        if (client.player.getAbilities().instabuild) {
            return -1;
        }

        Inventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        boolean silkRequired =
                this.silkTouch.get() && this.silkTouchBlocks.contains(state.getBlock());
        boolean cobweb = state.is(Blocks.COBWEB);
        int best = -1;
        int bestCobwebPriority = Integer.MIN_VALUE;
        float bestSpeed = -1.0F;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (!this.ignoreDurability.get()
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
        if (stack.is(Items.SHEARS)) {
            return 2;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return 1;
        }
        return 0;
    }

    private static float destroySpeedWithEnchantment(
            Minecraft client, ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        int efficiency = enchantmentLevel(client, stack, Enchantments.EFFICIENCY);

        if (speed > 1.0F && efficiency > 0) {
            speed += Math.min(1024.0F, efficiency * efficiency + 1.0F);
        }

        return speed;
    }

    private static int enchantmentLevel(
            Minecraft client, ItemStack stack, ResourceKey<Enchantment> enchantment) {
        var currentLevel = client == null ? null : client.level;
        if (currentLevel == null) {
            return 0;
        }

        Holder<Enchantment> holder =
                currentLevel
                        .registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT)
                        .get(enchantment)
                        .orElse(null);
        return holder == null ? 0 : stack.getEnchantments().getLevel(holder);
    }

    private static int hotbarDistance(int slot, int selected) {
        return Math.abs(slot - selected);
    }

    private void updateCombatLock(Minecraft client) {
        if (this.combatLockTicks > 0) {
            this.combatLockTicks--;
        }

        int hurtTime = client.player.hurtTime;
        boolean newlyHurt = hurtTime > this.lastPlayerHurtTime;
        this.lastPlayerHurtTime = hurtTime;
        if (newlyHurt
                && client.player.getLastHurtByMob() instanceof Player attacker
                && Targeting.isValidTargetPlayer(client, attacker)) {
            lockCombat();
        }
    }

    private boolean isInCombat(Minecraft client) {
        return this.combatLockTicks > 0 || hasImmediateThreat(client);
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
            Vec3 horizontalDirection = new Vec3(towardPlayer.x, 0.0D, towardPlayer.z);
            double directionLength = horizontalDirection.length();
            if (directionLength < 1.0E-5D) {
                return true;
            }
            horizontalDirection = horizontalDirection.scale(1.0D / directionLength);

            Vec3 look = target.getLookAngle();
            Vec3 horizontalLook = new Vec3(look.x, 0.0D, look.z);
            double lookLength = horizontalLook.length();
            if (lookLength < 1.0E-5D
                    || horizontalLook.scale(1.0D / lookLength).dot(horizontalDirection) < 0.35D) {
                continue;
            }

            Vec3 targetVelocity = target.getDeltaMovement();
            Vec3 playerVelocity = client.player.getDeltaMovement();
            Vec3 relativeVelocity =
                    new Vec3(
                            targetVelocity.x - playerVelocity.x,
                            0.0D,
                            targetVelocity.z - playerVelocity.z);
            if (relativeVelocity.dot(horizontalDirection) >= 0.025D) {
                return true;
            }
        }
        return false;
    }

    public void onAttack(Entity target) {
        Minecraft client = Minecraft.getInstance();
        if (this.enabled.get()
                && this.notDuringCombat.get()
                && Targeting.isEnemyPlayer(client, target)) {
            lockCombat();
        }
    }

    private void lockCombat() {
        this.combatLockTicks = Math.max(this.combatLockTicks, this.combatGraceTicks.get());
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoTool: "
                        + statusText()
                        + ", mode: "
                        + this.mode.serialized()
                        + ", slot: "
                        + this.staticSlot.get()
                        + ", ignoreDurability: "
                        + toggleText(this.ignoreDurability.get())
                        + ", silkTouch: "
                        + toggleText(this.silkTouch.get())
                        + ", sneaking: "
                        + toggleText(this.requireSneaking.get())
                        + ", combat: "
                        + toggleText(this.notDuringCombat.get())
                        + ", combatGrace: "
                        + this.combatGraceTicks.get()
                        + "t"
                        + ", keepSelected: enabled, cobweb: shears > sword"
                        + ". Usage: .tempos autotool <enable|disable|mode dynamic|static|slot 0-8|ignoredurability enable|disable|silktouch enable|disable|sneaking enable|disable|combat enable|disable|combatdelay 0-100>");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        if (!this.enabled.get()) {
            this.hotbar.release(client);
            this.manualOverride = false;
            this.combatLockTicks = 0;
            this.lastPlayerHurtTime = 0;
        }
        ClientChat.send(client, "AutoTool " + statusText() + ".");
        return 1;
    }

    public int setMode(Minecraft client, String value) {
        this.mode.deserialize(value);
        ClientChat.send(client, "AutoTool mode set to " + this.mode.serialized() + ".");
        return 1;
    }

    public int setStaticSlot(Minecraft client, int value) {
        this.staticSlot.set(value);
        ClientChat.send(client, "AutoTool slot set to " + this.staticSlot.get() + ".");
        return 1;
    }

    public int setIgnoreDurability(Minecraft client, boolean value) {
        this.ignoreDurability.set(value);
        ClientChat.send(
                client,
                "AutoTool ignoreDurability " + toggleText(this.ignoreDurability.get()) + ".");
        return 1;
    }

    public int setSilkTouch(Minecraft client, boolean value) {
        this.silkTouch.set(value);
        ClientChat.send(client, "AutoTool silkTouch " + toggleText(this.silkTouch.get()) + ".");
        return 1;
    }

    public int setRequireSneaking(Minecraft client, boolean value) {
        this.requireSneaking.set(value);
        ClientChat.send(
                client, "AutoTool requireSneaking " + toggleText(this.requireSneaking.get()) + ".");
        return 1;
    }

    public int setNotDuringCombat(Minecraft client, boolean value) {
        this.notDuringCombat.set(value);
        ClientChat.send(
                client, "AutoTool notDuringCombat " + toggleText(this.notDuringCombat.get()) + ".");
        return 1;
    }

    public int setCombatGraceTicks(Minecraft client, int value) {
        this.combatGraceTicks.set(value);
        ClientChat.send(
                client, "AutoTool combat delay set to " + this.combatGraceTicks.get() + " ticks.");
        return 1;
    }

    public boolean staticMode() {
        return this.mode.get() == Mode.STATIC;
    }

    private static String toggleText(boolean value) {
        return value ? "enabled" : "disabled";
    }

    private enum Mode {
        DYNAMIC,
        STATIC
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("autotool.enabled", false);
    }

    @Override
    protected String statusTag() {
        return this.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            mode.describeStored("mode", "Mode", this::setMode),
            staticSlot
                    .describeStored("slot", "Static slot", 1, this::setStaticSlot)
                    .visibleWhen(this::staticMode),
            ignoreDurability.describeStored(
                    "ignore_durability", "Ignore durability", this::setIgnoreDurability),
            silkTouch.describeStored("silk_touch", "Silk touch", this::setSilkTouch),
            requireSneaking.describeStored(
                    "sneaking", "Require sneaking", this::setRequireSneaking),
            notDuringCombat.describeStored(
                    "avoid_combat", "Avoid combat", this::setNotDuringCombat),
            combatGraceTicks.describeStored(
                    "combat_grace", "Combat grace", 1, this::setCombatGraceTicks)
        };
    }
}
