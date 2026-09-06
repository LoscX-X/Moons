package com.blanoir.moons.client.management.targeting;

import com.blanoir.moons.client.chat.ClientChat;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class Targeting {
    private static final BooleanSetting TEAM_CHECK_ENABLED =
            new BooleanSetting.Builder()
                    .name("targeting.team.enabled")
                    .defaultValue(true)
                    .build();
    private static final EquipmentSlot[] ARMOR_COLOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };
    private static Predicate<Entity> ignoreCheck = entity -> false;

    private Targeting() {
    }

    public static boolean isEnemyPlayer(Minecraft client, Entity entity) {
        if (client == null || client.player == null || entity == null) {
            return false;
        }

        if (!(entity instanceof Player target)) {
            return false;
        }

        return isValidTargetPlayer(client, target);
    }

    public static boolean isValidTargetPlayer(Minecraft client, Player target) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || target == null) {
            return false;
        }

        return target != currentPlayer
                && !target.isRemoved()
                && target.isAlive()
                && target.isAttackable()
                && !target.isSpectator()
                && !ignoreCheck.test(target)
                && !target.isInvisibleTo(currentPlayer)
                && (!TEAM_CHECK_ENABLED.get() || !isSameTeam(client, target));
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

    public static boolean isValidTargetPlayerWithinRange(Minecraft client, Player target, double range) {
        if (client.player == null) {
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
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || target == null) {
            return false;
        }

        double range = currentPlayer.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
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
            Collection<Identifier> entityTypes,
            boolean throughBlocks
    ) {
        return findTargetOnViewRay(
                client,
                entity -> isConfiguredTarget(
                        client, entity, targetPlayers, targetMobs, entityTypes),
                throughBlocks);
    }

    public static Entity findTargetOnRay(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double range,
            Predicate<Entity> predicate,
            boolean throughBlocks
    ) {
        if (client == null || client.player == null || client.level == null
                || start == null || look == null || look.lengthSqr() <= 1.0E-9D
                || !Double.isFinite(range) || range <= 0.0D) {
            return null;
        }
        Vec3 end = start.add(look.normalize().scale(range));
        EntityHitResult hit = RaytraceUtils.findEntity(
                client, start, end, range, throughBlocks, predicate);
        return hit == null ? null : hit.getEntity();
    }

    public static Entity findTargetOnViewRay(
            Minecraft client,
            Predicate<Entity> predicate,
            boolean throughBlocks
    ) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) {
            return null;
        }

        double range = currentPlayer.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        Vec3 start = currentPlayer.getEyePosition();
        return findTargetOnRay(
                client,
                start,
                currentPlayer.getViewVector(1.0F),
                range,
                predicate,
                throughBlocks);
    }

    public static boolean isConfiguredTarget(
            Minecraft client,
            Entity entity,
            boolean targetPlayers,
            boolean targetMobs,
            Collection<Identifier> entityTypes
    ) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null
                || !(entity instanceof LivingEntity living)
                || living == currentPlayer
                || !living.isAlive()
                || !living.isAttackable()
                || living.isSpectator()) {
            return false;
        }
        if (living instanceof Player player) {
            return targetPlayers && isValidTargetPlayer(client, player);
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
        return targetMobs && living instanceof Mob
                || entityTypes != null && entityTypes.contains(id);
    }

    public static Identifier parseEntityTypeId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        return Identifier.tryParse(normalized.contains(":")
                ? normalized : "minecraft:" + normalized);
    }

    public static Set<Identifier> parseEntityTypeIds(String stored) {
        Set<Identifier> result = new TreeSet<>();
        if (stored == null || stored.isBlank()) {
            return result;
        }
        for (String rawId : stored.split(",")) {
            Identifier id = parseEntityTypeId(rawId);
            if (id != null && BuiltInRegistries.ENTITY_TYPE.getOptional(id).isPresent()) {
                result.add(id);
            }
        }
        return result;
    }

    public static String serializeEntityTypeIds(Collection<Identifier> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return "";
        }
        return entityTypes.stream()
                .map(Identifier::toString)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    public static String configuredTargetStatus(
            boolean targetPlayers,
            boolean targetMobs,
            Collection<Identifier> entityTypes
    ) {
        String categories = targetPlayers
                ? (targetMobs ? "player,mob" : "player")
                : (targetMobs ? "mob" : "none");
        String specific = serializeEntityTypeIds(entityTypes);
        return specific.isEmpty() ? categories : categories + "; specific=" + specific;
    }

    public static boolean isAimingAtEnemy(
            Minecraft client,
            Entity target,
            boolean throughBlock
    ) {
        if (!isEnemyPlayer(client, target) || !isWithinInteractionRange(client, target)) {
            return false;
        }
        if (client.hitResult instanceof EntityHitResult hit && hit.getEntity() == target) {
            return true;
        }
        return throughBlock && findEnemyPlayerOnViewRay(client) == target;
    }

    public static boolean isHoldingTriggerWeapon(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null) {
            return false;
        }

        ItemStack stack = currentPlayer.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }

        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }

    public static void setIgnoreCheck(Predicate<Entity> check) {
        ignoreCheck = check == null ? entity -> false : check;
    }

    private static boolean isSameTeam(Minecraft client, Player target) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || target == null) {
            return false;
        }

        Player player = currentPlayer;
        return isScoreboardTeammate(player, target)
                || hasSameNameColor(player, target)
                || hasSameDisplayNamePrefix(player, target)
                || hasMatchingArmorColor(player, target);
    }

    private static boolean isScoreboardTeammate(Player player, Player target) {
        return player.isAlliedTo(target) || target.isAlliedTo(player);
    }

    private static boolean hasSameNameColor(Player player, Player target) {
        TextColor playerColor = player.getDisplayName().getStyle().getColor();
        TextColor targetColor = target.getDisplayName().getStyle().getColor();

        return playerColor != null && targetColor != null && playerColor.equals(targetColor);
    }

    private static boolean hasSameDisplayNamePrefix(Player player, Player target) {
        String playerPrefix = firstDisplayNamePart(player);
        String targetPrefix = firstDisplayNamePart(target);

        return playerPrefix != null && playerPrefix.equals(targetPrefix);
    }

    private static String firstDisplayNamePart(Player player) {
        if (player.getDisplayName() == null) {
            return null;
        }

        String strippedName = ChatFormatting.stripFormatting(player.getDisplayName().getString());
        if (strippedName == null) {
            return null;
        }

        String[] parts = strippedName.trim().split("\\s+");
        return parts.length > 1 ? parts[0] : null;
    }

    private static boolean hasMatchingArmorColor(Player player, Player target) {
        for (EquipmentSlot slot : ARMOR_COLOR_SLOTS) {
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

    private static Integer armorColor(Player player, EquipmentSlot slot) {
        DyedItemColor dyedColor = player.getItemBySlot(slot).get(DataComponents.DYED_COLOR);
        return dyedColor == null ? null : dyedColor.rgb();
    }
}
