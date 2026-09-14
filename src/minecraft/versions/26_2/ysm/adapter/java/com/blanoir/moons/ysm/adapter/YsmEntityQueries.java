package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
            case "is_item_name_any", "get_equipped_item_name" -> {
                ItemStack item =
                        equipment(
                                player,
                                args.isEmpty() ? "mainhand" : String.valueOf(args.getFirst()));
                String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                yield name.equals("get_equipped_item_name")
                        ? id
                        : args.stream().skip(1).anyMatch(id::equals);
            }
            case "remaining_durability" -> {
                ItemStack item = equipment(player, String.valueOf(args.getFirst()));
                yield item.getMaxDamage() - item.getDamageValue();
            }
            case "max_durability" ->
                    equipment(player, String.valueOf(args.getFirst())).getMaxDamage();
            case "equipped_item_any_tag", "equipped_item_all_tags" -> {
                ItemStack item = equipment(player, String.valueOf(args.getFirst()));
                var matches =
                        args.stream()
                                .skip(1)
                                .map(
                                        v ->
                                                item.is(
                                                        TagKey.create(
                                                                Registries.ITEM,
                                                                Identifier.parse(
                                                                        String.valueOf(v)))));
                yield name.equals("equipped_item_all_tags")
                        ? matches.allMatch(Boolean::booleanValue)
                        : matches.anyMatch(Boolean::booleanValue);
            }
            case "relative_block_name", "relative_block_name_any" -> {
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
                        : args.stream().skip(3).anyMatch(id::equals);
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
                int total = 0;
                for (Object value : args) {
                    Identifier id = Identifier.tryParse(String.valueOf(value));
                    if (id == null) continue;
                    var holder = BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);
                    if (holder == null) continue;
                    var effect =
                            player instanceof LivingEntity living ? living.getEffect(holder) : null;
                    if (effect != null) total += effect.getAmplifier() + 1;
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
                (player instanceof LivingEntity living
                                ? living.getActiveEffects()
                                : List.<net.minecraft.world.effect.MobEffectInstance>of())
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
            case "keyboard" ->
                    org.lwjgl.glfw.GLFW.glfwGetKey(
                                    Minecraft.getInstance().getWindow().handle(), integer(args, 0))
                            == 1;
            case "mouse" ->
                    org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                                    Minecraft.getInstance().getWindow().handle(), integer(args, 0))
                            == 1;
            default -> {
                diagnostic.accept("Unbound observation: " + namespace + "." + name);
                yield 0f;
            }
        };
    }

    private static ItemStack equipment(Entity player, String slot) {
        if (!(player instanceof LivingEntity living)) return ItemStack.EMPTY;
        return living.getItemBySlot(
                switch (slot.replace("slot.", "")) {
                    case "offhand", "weapon.offhand" -> EquipmentSlot.OFFHAND;
                    case "head", "armor.head" -> EquipmentSlot.HEAD;
                    case "chest", "armor.chest" -> EquipmentSlot.CHEST;
                    case "legs", "armor.legs" -> EquipmentSlot.LEGS;
                    case "feet", "armor.feet" -> EquipmentSlot.FEET;
                    default -> EquipmentSlot.MAINHAND;
                });
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
