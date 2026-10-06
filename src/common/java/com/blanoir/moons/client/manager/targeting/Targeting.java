package com.blanoir.moons.client.manager.targeting;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.item.EquipmentSlot;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.entity.EntityTypeIds;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

public final class Targeting {
    private static final BooleanSetting TEAM_CHECK_ENABLED =
            new BooleanSetting.Builder().name("targeting.team.enabled").defaultValue(true).build();
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static Predicate<Entity> ignoreCheck = entity -> false;

    private Targeting() {}

    public static boolean isEnemyPlayer(Minecraft client, Entity entity) {
        if (client == null || client.thePlayer == null || entity == null) {
            return false;
        }

        if (!(entity instanceof EntityPlayer target)) {
            return false;
        }

        return isValidTargetPlayer(client, target);
    }

    public static boolean isValidTargetPlayer(Minecraft client, EntityPlayer target) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || target == null) {
            return false;
        }

        return target != currentPlayer
                && !target.isDead
                && target.isEntityAlive()
                && target.canAttackWithItem()
                && !target.isSpectator()
                && !ignoreCheck.test(target)
                && (!target.isInvisibleToPlayer(currentPlayer) || hasExposedEquipment(target))
                && (!TEAM_CHECK_ENABLED.get() || !isSameTeam(client, target));
    }

    private static boolean hasExposedEquipment(EntityPlayer target) {
        // Read current equipment every time so equipping or stowing it takes effect immediately.
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (target.inventory.armorInventory[slot.index()] != null) {
                return true;
            }
        }
        return target.getHeldItem() != null;
    }

    public static boolean isTeamCheckEnabled() {
        return TEAM_CHECK_ENABLED.get();
    }

    public static String teamStatusText() {
        return TEAM_CHECK_ENABLED.get() ? "enabled" : "disabled";
    }

    public static void setTeamCheckEnabled(Minecraft client, boolean newEnabled) {
        TEAM_CHECK_ENABLED.set(newEnabled);
        ClientChat.send(client, "Team check " + teamStatusText() + ".");
    }

    public static boolean isValidTargetPlayerWithinRange(
            Minecraft client, EntityPlayer target, double range) {
        if (client.thePlayer == null) {
            return false;
        }
        if (!isValidTargetPlayer(client, target)) {
            return false;
        }

        return EntityDistance.squaredToEntity(client, target) <= range * range;
    }

    /**
     * Mirrors vanilla attack reach: the player's eye position must be within the
     * ENTITY_INTERACTION_RANGE attribute of the closest point on the target's bounding
     * box.  Entity-center distance is not used because it overestimates reach for
     * large entities and underestimates it for small ones.
     */
    public static boolean isWithinInteractionRange(Minecraft client, Entity target) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || target == null) {
            return false;
        }

        double range = CombatReach.vanillaEntityInteractionRange(currentPlayer);
        return EntityDistance.squaredToEntity(client, target) <= range * range;
    }

    /**
     * Finds the first enemy player intersected by the player's interaction-range
     * view ray without clipping that ray against blocks. This is intentionally
     * separate from Minecraft's normal hit result so callers must explicitly opt
     * into through-block targeting.
     */
    public static Entity findEnemyPlayerOnViewRay(Minecraft client) {
        return findTargetOnViewRay(client, entity -> isEnemyPlayer(client, entity), true);
    }

    public static Entity findConfiguredTargetOnViewRay(
            Minecraft client,
            boolean targetPlayers,
            boolean targetMobs,
            Collection<ResourceLocation> entityTypes,
            boolean throughBlocks) {
        return findTargetOnViewRay(
                client,
                entity ->
                        isConfiguredTarget(client, entity, targetPlayers, targetMobs, entityTypes),
                throughBlocks);
    }

    public static Entity findTargetOnRay(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double range,
            Predicate<Entity> predicate,
            boolean throughBlocks) {
        return RaytraceUtils.findEntityOnRay(client, start, look, range, predicate, throughBlocks);
    }

    public static Entity findTargetOnViewRay(
            Minecraft client, Predicate<Entity> predicate, boolean throughBlocks) {
        return RaytraceUtils.findEntityOnViewRay(client, predicate, throughBlocks);
    }

    public static boolean isConfiguredTarget(
            Minecraft client,
            Entity entity,
            boolean targetPlayers,
            boolean targetMobs,
            Collection<ResourceLocation> entityTypes) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null
                || currentPlayer == null
                || !(entity instanceof EntityLivingBase living)
                || living == currentPlayer
                || !living.isEntityAlive()
                || !living.canAttackWithItem()
                || living instanceof EntityPlayer p && p.isSpectator()) {
            return false;
        }
        if (living instanceof EntityPlayer player) {
            return targetPlayers && isValidTargetPlayer(client, player);
        }
        ResourceLocation id = EntityTypeIds.id(living);
        return targetMobs && living instanceof EntityLiving
                || entityTypes != null && entityTypes.contains(id);
    }

    public static ResourceLocation parseEntityTypeId(String rawId) {
        return EntityTypeIds.parse(rawId);
    }

    public static Set<ResourceLocation> parseEntityTypeIds(String stored) {
        return EntityTypeIds.parseKnown(stored);
    }

    public static String serializeEntityTypeIds(Collection<ResourceLocation> entityTypes) {
        return EntityTypeIds.serialize(entityTypes);
    }

    public static String configuredTargetStatus(
            boolean targetPlayers, boolean targetMobs, Collection<ResourceLocation> entityTypes) {
        String categories =
                targetPlayers
                        ? (targetMobs ? "player,mob" : "player")
                        : (targetMobs ? "mob" : "none");
        String specific = serializeEntityTypeIds(entityTypes);
        return specific.isEmpty() ? categories : categories + "; specific=" + specific;
    }

    public static boolean isAimingAtEnemy(Minecraft client, Entity target, boolean throughBlock) {
        if (!isEnemyPlayer(client, target) || !isWithinInteractionRange(client, target)) {
            return false;
        }
        if (client.objectMouseOver != null
                && client.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && client.objectMouseOver.entityHit == target) {
            return true;
        }
        return throughBlock && findEnemyPlayerOnViewRay(client) == target;
    }

    public static boolean isHoldingTriggerWeapon(Minecraft client) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null) {
            return false;
        }

        ItemStack stack = currentPlayer.getHeldItem();
        if (stack == null) {
            return false;
        }

        return stack.getItem() instanceof ItemSword || stack.getItem() instanceof ItemAxe;
    }

    public static void setIgnoreCheck(Predicate<Entity> check) {
        ignoreCheck = check == null ? entity -> false : check;
    }

    private static boolean isSameTeam(Minecraft client, EntityPlayer target) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || target == null) {
            return false;
        }

        EntityPlayer player = currentPlayer;
        return isScoreboardTeammate(player, target)
                || hasSameNameColor(player, target)
                || hasSameDisplayNamePrefix(player, target)
                || hasMatchingArmorColor(player, target);
    }

    private static boolean isScoreboardTeammate(EntityPlayer player, EntityPlayer target) {
        return player.isOnSameTeam(target) || target.isOnSameTeam(player);
    }

    private static boolean hasSameNameColor(EntityPlayer player, EntityPlayer target) {
        EnumChatFormatting playerColor = player.getDisplayName().getChatStyle().getColor();
        EnumChatFormatting targetColor = target.getDisplayName().getChatStyle().getColor();

        return playerColor != null && targetColor != null && playerColor.equals(targetColor);
    }

    private static boolean hasSameDisplayNamePrefix(EntityPlayer player, EntityPlayer target) {
        String playerPrefix = firstDisplayNamePart(player);
        String targetPrefix = firstDisplayNamePart(target);

        return playerPrefix != null && playerPrefix.equals(targetPrefix);
    }

    private static String firstDisplayNamePart(EntityPlayer player) {
        if (player.getDisplayName() == null) {
            return null;
        }

        String strippedName =
                EnumChatFormatting.getTextWithoutFormattingCodes(
                        player.getDisplayName().getUnformattedText());
        if (strippedName == null) {
            return null;
        }

        String[] parts = strippedName.trim().split("\\s+");
        return parts.length > 1 ? parts[0] : null;
    }

    private static boolean hasMatchingArmorColor(EntityPlayer player, EntityPlayer target) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            Integer playerColor = armorColor(player, slot);
            if (playerColor == null) {
                continue;
            }

            Integer targetColor = armorColor(target, slot);
            if (playerColor.equals(targetColor)) {
                return true;
            }
        }

        return false;
    }

    private static Integer armorColor(EntityPlayer player, EquipmentSlot slot) {
        ItemStack stack = player.inventory.armorInventory[slot.index()];
        return stack != null && stack.getItem() instanceof ItemArmor armor && armor.hasColor(stack)
                ? armor.getColor(stack)
                : null;
    }
}
