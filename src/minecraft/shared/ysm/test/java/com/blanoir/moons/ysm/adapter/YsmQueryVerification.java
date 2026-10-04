package com.blanoir.moons.ysm.adapter;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import java.util.*;

/** Real 1.8 registries and stacks, without constructing Minecraft, a world or a GPU. */
public final class YsmQueryVerification {
    public static void main(String[] args) throws Exception {
        Bootstrap.register();
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var entity = (EquippedStand) unsafe.allocateInstance(EquippedStand.class);
        entity.items = new ItemStack[5];
        entity.effects = List.of(new PotionEffect(Potion.poison.id, 200, 2));
        entity.items[0] = new ItemStack(Items.stick);
        equal(
                "stick",
                query(entity, "get_equipped_item_name", "main_hand"),
                "Main-hand short name");
        equal("empty", query(entity, "get_equipped_item_name", "off_hand"), "Absent offhand");
        equal(true, query(entity, "is_item_name_any", "mainhand", "stick"), "Default namespace");
        equal(true, query(entity, "is_item_name_any", "MAINHAND", "minecraft:stick"), "Slot case");
        equal(
                false,
                query(entity, "is_item_name_any", "mainhand", "other:stick"),
                "Foreign namespace");
        equal(null, query(entity, "is_item_name_any", "invalid", "stick"), "Invalid slot");
        equal(
                null,
                query(entity, "is_item_name_any", "mainhand", "invalid name", "stick"),
                "Invalid ID short circuit");
        equal(
                true,
                query(entity, "is_item_name_any", "mainhand", "stick", "invalid name"),
                "Match short circuit");
        equal(
                null,
                query(entity, "equipped_item_any_tag", "mainhand", "invalid tag"),
                "Invalid tag");
        equal(
                false,
                query(entity, "equipped_item_any_tag", "mainhand", "minecraft:sticks"),
                "No fabricated registry tags");
        equal(null, query(entity, "remaining_durability"), "Missing argument");
        equal(null, query(entity, "max_durability", "invalid"), "Invalid slot");
        equal(null, query(entity, "effect_level"), "Missing effect");
        equal(3, query(entity, "effect_level", "poison"), "Potion amplifier");
        equal(0, query(entity, "effect_level", "speed", "invalid effect"), "Absent effects");
        var sword = new ItemStack(Items.diamond_sword);
        sword.setItemDamage(5);
        sword.addEnchantment(Enchantment.sharpness, 3);
        entity.items[0] = sword;
        equal(
                sword.getMaxDamage() - 5,
                query(entity, "remaining_durability", "mainhand"),
                "Durability");
        equal(
                3,
                query(entity, "equipped_enchantment_level", "mainhand", "sharpness"),
                "Enchantment registry ID");
        verifyEquipment(entity);
        entity.items = new ItemStack[5];
        equal("empty", query(entity, "get_equipped_item_name", "main_hand"), "Empty name");
        equal(
                false,
                query(entity, "is_item_name_any", "mainhand", "air"),
                "Empty stack never matches air");
        var arrow = (EntityArrow) unsafe.allocateInstance(EntityArrow.class);
        equal(0, query(arrow, "effect_level", "poison"), "1.8 arrows have no potion effects");
        Map<String, Object> boat = new HashMap<>();
        YsmAdditionalObservations.boat(boat, arrow, .5f);
        equal(false, boat.get("boat_is_chest"), "No chest boats");
        equal(false, boat.get("boat_is_raft"), "No rafts");
        equal(false, boat.get("boat_left_paddle"), "No 1.9 paddle state");
        equal(0, boat.get("boat_left_rowing_time"), "Neutral paddle time");
        Object noise = query(entity, "perlin_noise", 7, .3, .4, .5);
        equal(noise, query(entity, "perlin_noise", 7, .3, .4, .5), "Deterministic seeded noise");
        if (noise.equals(query(entity, "perlin_noise", 8, .3, .4, .5)))
            throw new AssertionError("Noise seed ignored");
        System.out.println(
                "YSM_QUERIES_VERIFIED equipment=128 native-slots+categories+snapshot effects=living+normal-arrow enchantments=real-registry identifiers=strict noise=seeded");
    }

    private static void verifyEquipment(EquippedStand entity) {
        var samples =
                new ItemStack[] {
                    null,
                    new ItemStack(Items.stick),
                    new ItemStack(Items.diamond_sword),
                    new ItemStack(Items.iron_axe),
                    new ItemStack(Items.bow),
                    new ItemStack(Items.potionitem, 1, 16384)
                };
        var names = new String[] {"empty", "stick", "sword", "axe", "bow", "throwable_potion"};
        Random random = new Random(20260925);
        for (int sample = 0; sample < 128; sample++) {
            int count = 0;
            int[] choices = new int[5];
            entity.items = new ItemStack[5];
            for (int i = 0; i < 5; i++) {
                choices[i] = random.nextInt(samples.length);
                entity.items[i] = samples[choices[i]];
                if (i > 0 && entity.items[i] != null) count++;
            }
            Map<String, Object> q = new LinkedHashMap<>();
            equal(count, YsmEquipmentObservations.sample(q, entity), "Armor count");
            String[] slots = {"mainhand", "feet", "legs", "chest", "head"};
            for (int i = 0; i < 5; i++) {
                equal(entity.items[i] != null, q.get("has_" + slots[i]), "Equipment presence");
                equal(names[choices[i]], q.get(slots[i] + "_category"), "Native category");
            }
            equal(false, q.get("has_offhand"), "Absent offhand");
            equal(List.of(), q.get("head_tags"), "No registry tags");
            var retained = new LinkedHashMap<>(q);
            YsmEquipmentObservations.sample(new HashMap<>(), entity);
            equal(retained, q, "Stable snapshot");
        }
    }

    private static Object query(Entity e, String name, Object... args) {
        return YsmEntityQueries.query(
                e,
                Map.of(),
                message -> {
                    throw new AssertionError(message);
                },
                "ysm",
                name,
                Arrays.asList(args));
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class EquippedStand extends EntityArmorStand {
        ItemStack[] items;
        Collection<PotionEffect> effects;

        EquippedStand() {
            super(null, 0, 0, 0);
        }

        @Override
        public ItemStack getHeldItem() {
            return items[0];
        }

        @Override
        public ItemStack getCurrentArmor(int slot) {
            return items[slot + 1];
        }

        @Override
        public Collection<PotionEffect> getActivePotionEffects() {
            return effects;
        }
    }
}
