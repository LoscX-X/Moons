package com.blanoir.moons.ysm.adapter;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.gen.NoiseGeneratorImproved;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.*;
import java.util.function.Consumer;

/** Entity queries over real 1.8 registries, nullable stacks, enchantments and LWJGL 2 input. */
final class YsmEntityQueries {
    private static final Map<Integer, NoiseGeneratorImproved> NOISE =
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Integer, NoiseGeneratorImproved> entry) {
                    return size() > 32;
                }
            };

    static Object query(
            Entity entity,
            Map<String, Object> snapshot,
            Consumer<String> diagnostic,
            String namespace,
            String name,
            List<Object> args) {
        if (entity == null) return 0f;
        var level = entity.worldObj;
        return switch (name) {
            case "position", "position_delta" -> {
                int axis = integer(args, 0);
                yield axis < 0 || axis > 2
                        ? null
                        : snapshot.getOrDefault(
                                (name.equals("position") ? "position_" : "delta_")
                                        + (axis == 0 ? "x" : axis == 1 ? "y" : "z"),
                                0f);
            }
            case "get_equipped_item_name" -> {
                if (!(entity instanceof EntityLivingBase) || args.size() != 1) yield null;
                ItemStack stack =
                        equipment(
                                entity,
                                "off_hand".equals(args.getFirst()) ? "offhand" : "mainhand");
                yield stack == null
                        ? "empty"
                        : Item.itemRegistry.getNameForObject(stack.getItem()).getResourcePath();
            }
            case "is_item_name_any" -> {
                if (!validEquipmentArgs(entity, args, 2)) yield null;
                var stack = equipment(entity, String.valueOf(args.getFirst()));
                if (stack == null) yield false;
                yield matchesId(Item.itemRegistry.getNameForObject(stack.getItem()), args, 1, true);
            }
            case "remaining_durability", "max_durability" -> {
                if (!validEquipmentArgs(entity, args, 1) || args.size() != 1) yield null;
                var stack = equipment(entity, String.valueOf(args.getFirst()));
                yield stack == null
                        ? 0
                        : name.equals("max_durability")
                                ? stack.getMaxDamage()
                                : stack.getMaxDamage() - stack.getItemDamage();
            }
            case "equipped_item_any_tag", "equipped_item_all_tags" -> {
                if (!validEquipmentArgs(entity, args, 2)) yield null;
                if (args.subList(1, args.size()).stream()
                        .anyMatch(value -> parse(String.valueOf(value)) == null)) yield null;
                yield false; // Data-pack item tags do not exist in vanilla 1.8.
            }
            case "relative_block_name", "relative_block_name_any" -> {
                if (name.equals("relative_block_name") ? args.size() != 3 : args.size() < 4)
                    yield null;
                BlockPos pos = relativeBlock(entity, args);
                ResourceLocation id =
                        pos == null || level == null
                                ? null
                                : Block.blockRegistry.getNameForObject(
                                        level.getBlockState(pos).getBlock());
                yield name.equals("relative_block_name")
                        ? id == null ? "" : id.toString()
                        : id != null && matchesId(id, args, 3, false);
            }
            case "biome_has_all_tags",
                    "biome_has_any_tag",
                    "relative_block_has_all_tags",
                    "relative_block_has_any_tag" ->
                    false;
            case "effect_level" -> {
                if (args.isEmpty()) yield null;
                int total = 0;
                for (Object value : args) {
                    ResourceLocation id = parse(String.valueOf(value));
                    Potion potion =
                            id == null ? null : Potion.getPotionFromResourceLocation(id.toString());
                    if (potion == null) continue;
                    for (PotionEffect effect : effects(entity))
                        if (effect.getPotionID() == potion.id) {
                            total += effect.getAmplifier() + 1;
                            break;
                        }
                }
                yield total;
            }
            case "equipped_enchantment_level" -> {
                if (args.size() < 2) yield 0;
                var stack = equipment(entity, String.valueOf(args.getFirst()));
                if (stack == null) yield 0;
                int total = 0;
                for (Object value : args.subList(1, args.size())) {
                    ResourceLocation id = parse(String.valueOf(value));
                    Enchantment enchantment =
                            id == null ? null : Enchantment.getEnchantmentByLocation(id.toString());
                    if (enchantment != null)
                        total += EnchantmentHelper.getEnchantmentLevel(enchantment.effectId, stack);
                }
                yield total;
            }
            case "perlin_noise" ->
                    noise(integer(args, 0), decimal(args, 1), decimal(args, 2), decimal(args, 3));
            case "rotation_to_camera" -> {
                var manager = Minecraft.getMinecraft().getRenderManager();
                yield integer(args, 0) == 0 ? manager.playerViewX : manager.playerViewY;
            }
            case "biome_category" -> null;
            case "debug_output" -> {
                diagnostic.accept("Molang: " + args);
                yield null;
            }
            case "dump_equipped_item" -> {
                for (String slot : YsmEquipmentObservations.SLOTS) {
                    var stack = equipment(entity, slot);
                    diagnostic.accept(
                            slot
                                    + ": "
                                    + (stack == null
                                            ? "empty"
                                            : Item.itemRegistry.getNameForObject(stack.getItem()))
                                    + " · tags=[]");
                }
                yield null;
            }
            case "dump_relative_block" -> {
                var pos = relativeBlock(entity, args);
                if (level != null && pos != null)
                    diagnostic.accept("Block " + pos + ": " + level.getBlockState(pos));
                yield null;
            }
            case "dump_effects" -> {
                for (PotionEffect effect : effects(entity))
                    diagnostic.accept(
                            "Effect "
                                    + effect.getEffectName()
                                    + " level="
                                    + (effect.getAmplifier() + 1)
                                    + " ticks="
                                    + effect.getDuration());
                yield null;
            }
            case "dump_biome" -> {
                if (level != null)
                    diagnostic.accept(
                            "Biome "
                                    + level.getBiomeGenForCoords(new BlockPos(entity)).biomeName
                                    + " · tags=[]");
                yield null;
            }
            case "keyboard" -> {
                int code = keyboardCode(integer(args, 0));
                yield Keyboard.isCreated()
                        && code > 0
                        && code < Keyboard.KEYBOARD_SIZE
                        && Keyboard.isKeyDown(code);
            }
            case "mouse" -> {
                int button = integer(args, 0);
                yield Mouse.isCreated()
                        && button >= 0
                        && button < Mouse.getButtonCount()
                        && Mouse.isButtonDown(button);
            }
            default -> {
                diagnostic.accept("Unbound observation: " + namespace + "." + name);
                yield 0f;
            }
        };
    }

    static ResourceLocation entityId(Entity entity) {
        if (entity instanceof EntityPlayer) return new ResourceLocation("player");
        String name = EntityList.getEntityString(entity);
        if (name == null) return new ResourceLocation("unknown");
        return new ResourceLocation(
                name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
    }

    private static ResourceLocation parse(String value) {
        if (value == null || !value.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        return new ResourceLocation(value);
    }

    private static Boolean matchesId(
            ResourceLocation actual, List<Object> args, int start, boolean strict) {
        for (int i = start; i < args.size(); i++) {
            ResourceLocation expected = parse(String.valueOf(args.get(i)));
            if (expected == null && strict) return null;
            if (actual.equals(expected)) return true;
        }
        return false;
    }

    private static boolean validEquipmentArgs(Entity entity, List<Object> args, int minimum) {
        return entity instanceof EntityLivingBase
                && args.size() >= minimum
                && YsmEquipmentObservations.validSlot(String.valueOf(args.getFirst()));
    }

    private static ItemStack equipment(Entity entity, String slot) {
        return entity instanceof EntityLivingBase living
                ? YsmEquipmentObservations.equipment(living, slot)
                : null;
    }

    private static Iterable<PotionEffect> effects(Entity entity) {
        return entity instanceof EntityLivingBase living
                ? living.getActivePotionEffects()
                : List.of();
    }

    private static BlockPos relativeBlock(Entity entity, List<Object> args) {
        double x = decimal(args, 0), y = decimal(args, 1), z = decimal(args, 2);
        if (!Double.isFinite(x + y + z) || Math.abs(x) > 5 || Math.abs(y) > 5 || Math.abs(z) > 5)
            return null;
        return new BlockPos(
                (int) Math.round(entity.posX + x - .5),
                (int) Math.round(entity.posY + y - .5),
                (int) Math.round(entity.posZ + z - .5));
    }

    private static int integer(List<Object> args, int index) {
        return index < args.size() && args.get(index) instanceof Number n ? n.intValue() : 0;
    }

    private static double decimal(List<Object> args, int index) {
        return index < args.size() && args.get(index) instanceof Number n ? n.doubleValue() : 0;
    }

    private static synchronized float noise(int seed, double x, double y, double z) {
        if (!Double.isFinite(x + y + z)) return 0;
        NoiseGeneratorImproved generator =
                NOISE.computeIfAbsent(
                        seed,
                        key -> {
                            var g = new NoiseGeneratorImproved(new Random(key));
                            g.xCoord = 0;
                            g.yCoord = 0;
                            g.zCoord = 0;
                            return g;
                        });
        // A two-sample column selects the native 3-D gradient path, not its flat 2-D shortcut.
        double[] values = new double[2];
        generator.populateNoiseArray(values, x, y, z, 1, 2, 1, 1, 1, 1, 1);
        return (float) values[0];
    }

    /** Molang's keyboard argument uses GLFW values, independent of the host LWJGL generation. */
    private static int keyboardCode(int key) {
        if (key >= 65 && key <= 90 || key >= 48 && key <= 57)
            return Keyboard.getKeyIndex(Character.toString((char) key));
        if (key >= 290 && key <= 304) return Keyboard.getKeyIndex("F" + (key - 289));
        if (key >= 320 && key <= 329) return Keyboard.getKeyIndex("NUMPAD" + (key - 320));
        return switch (key) {
            case 32 -> Keyboard.KEY_SPACE;
            case 39 -> Keyboard.KEY_APOSTROPHE;
            case 44 -> Keyboard.KEY_COMMA;
            case 45 -> Keyboard.KEY_MINUS;
            case 46 -> Keyboard.KEY_PERIOD;
            case 47 -> Keyboard.KEY_SLASH;
            case 59 -> Keyboard.KEY_SEMICOLON;
            case 61 -> Keyboard.KEY_EQUALS;
            case 91 -> Keyboard.KEY_LBRACKET;
            case 92 -> Keyboard.KEY_BACKSLASH;
            case 93 -> Keyboard.KEY_RBRACKET;
            case 96 -> Keyboard.KEY_GRAVE;
            case 256 -> Keyboard.KEY_ESCAPE;
            case 257 -> Keyboard.KEY_RETURN;
            case 258 -> Keyboard.KEY_TAB;
            case 259 -> Keyboard.KEY_BACK;
            case 260 -> Keyboard.KEY_INSERT;
            case 261 -> Keyboard.KEY_DELETE;
            case 262 -> Keyboard.KEY_RIGHT;
            case 263 -> Keyboard.KEY_LEFT;
            case 264 -> Keyboard.KEY_DOWN;
            case 265 -> Keyboard.KEY_UP;
            case 266 -> Keyboard.KEY_PRIOR;
            case 267 -> Keyboard.KEY_NEXT;
            case 268 -> Keyboard.KEY_HOME;
            case 269 -> Keyboard.KEY_END;
            case 280 -> Keyboard.KEY_CAPITAL;
            case 281 -> Keyboard.KEY_SCROLL;
            case 282 -> Keyboard.KEY_NUMLOCK;
            case 283 -> Keyboard.KEY_SYSRQ;
            case 284 -> Keyboard.KEY_PAUSE;
            case 330 -> Keyboard.KEY_DECIMAL;
            case 331 -> Keyboard.KEY_DIVIDE;
            case 332 -> Keyboard.KEY_MULTIPLY;
            case 333 -> Keyboard.KEY_SUBTRACT;
            case 334 -> Keyboard.KEY_ADD;
            case 335 -> Keyboard.KEY_NUMPADENTER;
            case 336 -> Keyboard.KEY_NUMPADEQUALS;
            case 340 -> Keyboard.KEY_LSHIFT;
            case 341 -> Keyboard.KEY_LCONTROL;
            case 342 -> Keyboard.KEY_LMENU;
            case 343 -> Keyboard.KEY_LMETA;
            case 344 -> Keyboard.KEY_RSHIFT;
            case 345 -> Keyboard.KEY_RCONTROL;
            case 346 -> Keyboard.KEY_RMENU;
            case 347 -> Keyboard.KEY_RMETA;
            case 348 -> Keyboard.KEY_APPS;
            default -> Keyboard.KEY_NONE;
        };
    }
}
