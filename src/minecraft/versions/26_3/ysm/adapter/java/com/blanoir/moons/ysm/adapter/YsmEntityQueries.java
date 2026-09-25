package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;

import java.util.*;
import java.util.function.Consumer;

/** Entity queries shared by player, local mount and owned projectile adapters. */
final class YsmEntityQueries {
    static Object query(
            Entity player,
            Map<String, Object> snapshot,
            Consumer<String> diagnostic,
            String namespace,
            String name,
            List<Object> args) {
        if (player == null) return 0f;
        var level = player.level();
        return switch (name) {
            case "position", "position_delta" -> {
                int axis = integer(args, 0);
                if (axis < 0 || axis > 2) yield null;
                yield snapshot.getOrDefault(
                        (name.equals("position") ? "position_" : "delta_")
                                + (axis == 0 ? "x" : axis == 1 ? "y" : "z"),
                        0f);
            }
            case "get_equipped_item_name" -> {
                if (!(player instanceof LivingEntity) || args.size() != 1) yield null;
                ItemStack item =
                        equipment(
                                player,
                                "off_hand".equals(args.getFirst()) ? "offhand" : "mainhand");
                yield item.isEmpty()
                        ? "empty"
                        : BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
            }
            case "is_item_name_any" -> {
                if (!(player instanceof LivingEntity)
                        || args.size() < 2
                        || slot(String.valueOf(args.getFirst())) == null) yield null;
                ItemStack item = equipment(player, String.valueOf(args.getFirst()));
                if (item.isEmpty()) yield false;
                yield matchesId(BuiltInRegistries.ITEM.getKey(item.getItem()), args, 1, true);
            }
            case "remaining_durability", "max_durability" -> {
                if (!(player instanceof LivingEntity)
                        || args.size() != 1
                        || slot(String.valueOf(args.getFirst())) == null) yield null;
                ItemStack item = equipment(player, String.valueOf(args.getFirst()));
                yield name.equals("max_durability")
                        ? item.getMaxDamage()
                        : item.getMaxDamage() - item.getDamageValue();
            }
            case "equipped_item_any_tag", "equipped_item_all_tags" -> {
                if (!(player instanceof LivingEntity)
                        || args.size() < 2
                        || slot(String.valueOf(args.getFirst())) == null) yield null;
                ItemStack item = equipment(player, String.valueOf(args.getFirst()));
                if (item.isEmpty()) yield false;
                boolean all = name.equals("equipped_item_all_tags");
                Boolean result = all;
                for (int i = 1; i < args.size(); i++) {
                    Identifier id = Identifier.tryParse(String.valueOf(args.get(i)));
                    if (id == null) {
                        result = null;
                        break;
                    }
                    boolean matches = item.is(TagKey.create(Registries.ITEM, id));
                    if (matches != all) {
                        result = matches;
                        break;
                    }
                }
                yield result;
            }
            case "relative_block_name", "relative_block_name_any" -> {
                if (name.equals("relative_block_name") ? args.size() != 3 : args.size() < 4)
                    yield null;
                BlockPos pos = relativeBlock(player, args);
                if (pos == null) yield name.equals("relative_block_name") ? null : false;
                String id =
                        level == null
                                ? ""
                                : BuiltInRegistries.BLOCK
                                        .getKey(level.getBlockState(pos).getBlock())
                                        .toString();
                yield name.equals("relative_block_name")
                        ? id
                        : matchesId(Identifier.tryParse(id), args, 3, false);
            }
            case "biome_has_all_tags", "biome_has_any_tag" -> {
                if (level == null) yield false;
                var biome = level.getBiome(player.blockPosition());
                var matches =
                        args.stream()
                                .map(
                                        v ->
                                                biome.is(
                                                        TagKey.create(
                                                                Registries.BIOME,
                                                                Identifier.parse(
                                                                        String.valueOf(v)))));
                yield name.equals("biome_has_all_tags")
                        ? matches.allMatch(Boolean::booleanValue)
                        : matches.anyMatch(Boolean::booleanValue);
            }
            case "relative_block_has_all_tags", "relative_block_has_any_tag" -> {
                if (level == null || args.size() < 4) yield false;
                BlockPos pos = relativeBlock(player, args);
                if (pos == null) yield false;
                var block = level.getBlockState(pos);
                var matches =
                        args.stream()
                                .skip(3)
                                .map(
                                        v ->
                                                block.is(
                                                        TagKey.create(
                                                                Registries.BLOCK,
                                                                Identifier.parse(
                                                                        String.valueOf(v)))));
                yield name.equals("relative_block_has_all_tags")
                        ? matches.allMatch(Boolean::booleanValue)
                        : matches.anyMatch(Boolean::booleanValue);
            }
            case "effect_level" -> {
                if (args.isEmpty()) yield null;
                int total = 0;
                for (Object value : args) {
                    Identifier id = Identifier.tryParse(String.valueOf(value));
                    if (id == null) continue;
                    var holder = BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);
                    if (holder == null) continue;
                    if (!(player instanceof LivingEntity) && !(player instanceof Arrow)) yield null;
                    for (MobEffectInstance effect : effects(player)) {
                        if (effect.getEffect().value() == holder.value()) {
                            total += effect.getAmplifier() + 1;
                            break;
                        }
                    }
                }
                yield total;
            }
            case "equipped_enchantment_level" -> {
                if (args.size() < 2 || level == null) yield 0;
                ItemStack stack = equipment(player, String.valueOf(args.getFirst()));
                int total = 0;
                for (Object value : args.subList(1, args.size())) {
                    Identifier id = Identifier.tryParse(String.valueOf(value));
                    if (id == null) continue;
                    var enchantment =
                            level.registryAccess()
                                    .lookupOrThrow(Registries.ENCHANTMENT)
                                    .get(id)
                                    .orElse(null);
                    if (enchantment != null)
                        total +=
                                net.minecraft.world.item.enchantment.EnchantmentHelper
                                        .getItemEnchantmentLevel(enchantment, stack);
                }
                yield total;
            }
            case "perlin_noise" ->
                    org.lwjgl.stb.STBPerlin.stb_perlin_noise3_seed(
                            (float) decimal(args, 1),
                            (float) decimal(args, 2),
                            (float) decimal(args, 3),
                            0,
                            0,
                            0,
                            integer(args, 0));
            case "rotation_to_camera" ->
                    integer(args, 0) == 0
                            ? Minecraft.getInstance().gameRenderer.mainCamera().xRot()
                            : Minecraft.getInstance().gameRenderer.mainCamera().yRot();
            case "biome_category" -> null; // Deprecated upstream query also returns null.
            case "debug_output" -> {
                diagnostic.accept("Molang: " + args);
                yield null;
            }
            case "dump_equipped_item" -> {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    var stack = equipment(player, slot.getName());
                    diagnostic.accept(
                            slot.getName()
                                    + ": "
                                    + BuiltInRegistries.ITEM.getKey(stack.getItem())
                                    + " · tags="
                                    + snapshot.get(slot.getName() + "_tags"));
                }
                yield null;
            }
            case "dump_relative_block" -> {
                if (level != null) {
                    var pos = relativeBlock(player, args);
                    if (pos != null)
                        diagnostic.accept("Block " + pos + ": " + level.getBlockState(pos));
                }
                yield null;
            }
            case "dump_effects" -> {
                effects(player)
                        .forEach(
                                effect ->
                                        diagnostic.accept(
                                                "Effect "
                                                        + BuiltInRegistries.MOB_EFFECT.getKey(
                                                                effect.getEffect().value())
                                                        + " level="
                                                        + (effect.getAmplifier() + 1)
                                                        + " ticks="
                                                        + effect.getDuration()));
                yield null;
            }
            case "dump_biome" -> {
                if (level != null) {
                    var biome = level.getBiome(player.blockPosition());
                    diagnostic.accept(
                            "Biome "
                                    + biome.unwrapKey()
                                    + " · tags="
                                    + biome.tags().map(t -> t.location().toString()).toList());
                }
                yield null;
            }
            case "keyboard" -> YsmInput.keyboard(integer(args, 0));
            case "mouse" -> YsmInput.mouse(integer(args, 0));
            default -> {
                diagnostic.accept("Unbound observation: " + namespace + "." + name);
                yield 0f;
            }
        };
    }

    private static ItemStack equipment(Entity player, String slot) {
        if (!(player instanceof LivingEntity living)) return ItemStack.EMPTY;
        EquipmentSlot selected = slot(slot);
        return selected == null ? ItemStack.EMPTY : living.getItemBySlot(selected);
    }

    private static EquipmentSlot slot(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private static Boolean matchesId(
            Identifier actual, List<Object> args, int start, boolean strict) {
        if (actual == null) return false;
        for (int i = start; i < args.size(); i++) {
            Identifier expected = Identifier.tryParse(String.valueOf(args.get(i)));
            if (expected == null && strict) return null;
            if (expected != null && expected.equals(actual)) return true;
        }
        return false;
    }

    private static Iterable<MobEffectInstance> effects(Entity entity) {
        if (entity instanceof LivingEntity living) return living.getActiveEffects();
        if (entity instanceof Arrow arrow) {
            // This public accessor reads the same stack as upstream's getPickupItem mixin.
            var contents = arrow.getPickupItemStackOrigin().get(DataComponents.POTION_CONTENTS);
            if (contents != null) return contents.getAllEffects();
        }
        return List.of();
    }

    private static BlockPos relativeBlock(Entity entity, List<Object> args) {
        double x = decimal(args, 0), y = decimal(args, 1), z = decimal(args, 2);
        if (!Double.isFinite(x + y + z) || Math.abs(x) > 5 || Math.abs(y) > 5 || Math.abs(z) > 5)
            return null;
        return new BlockPos(
                (int) Math.round(entity.getX() + x - .5),
                (int) Math.round(entity.getY() + y - .5),
                (int) Math.round(entity.getZ() + z - .5));
    }

    private static int integer(List<Object> args, int index) {
        return index < args.size() && args.get(index) instanceof Number n ? n.intValue() : 0;
    }

    private static double decimal(List<Object> args, int index) {
        return index < args.size() && args.get(index) instanceof Number n ? n.doubleValue() : 0;
    }
}
