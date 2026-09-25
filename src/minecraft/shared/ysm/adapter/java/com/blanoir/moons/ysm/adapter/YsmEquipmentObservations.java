package com.blanoir.moons.ysm.adapter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable query names are shared; stack contents and registry tags are always sampled live. */
final class YsmEquipmentObservations {
    private record SlotKeys(
            EquipmentSlot slot, String has, String item, String category, String use, String tags) {
        SlotKeys(EquipmentSlot slot) {
            this(
                    slot,
                    "has_" + slot.getName(),
                    slot.getName() + "_item",
                    slot.getName() + "_category",
                    slot.getName() + "_use",
                    slot.getName() + "_tags");
        }
    }

    private record Category(String name, TagKey<Item> tag) {
        Category(String name) {
            this(name, TagKey.create(Registries.ITEM, Identifier.withDefaultNamespace(name + "s")));
        }
    }

    private static final SlotKeys[] SLOTS =
            Arrays.stream(EquipmentSlot.values()).map(SlotKeys::new).toArray(SlotKeys[]::new);
    private static final List<Category> CATEGORIES =
            List.of(
                    new Category("sword"),
                    new Category("axe"),
                    new Category("pickaxe"),
                    new Category("shovel"),
                    new Category("hoe"));

    static int sample(Map<String, Object> observations, LivingEntity entity) {
        int equipped = 0;
        for (SlotKeys keys : SLOTS) {
            ItemStack stack = entity.getItemBySlot(keys.slot);
            observations.put(keys.has, !stack.isEmpty());
            observations.put(keys.item, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            observations.put(keys.category, category(stack));
            observations.put(keys.use, stack.getUseAnimation().name().toLowerCase(Locale.ROOT));
            observations.put(
                    keys.tags,
                    BuiltInRegistries.ITEM
                            .wrapAsHolder(stack.getItem())
                            .tags()
                            .map(tag -> tag.location().toString())
                            .toList());
            if (keys.slot.isArmor() && !stack.isEmpty()) equipped++;
        }
        return equipped;
    }

    static String category(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        if (stack.is(Items.CROSSBOW))
            return CrossbowItem.isCharged(stack) ? "charged_crossbow" : "crossbow";
        if (stack.is(Items.TRIDENT)) return "trident";
        if (stack.getUseAnimation() == ItemUseAnimation.SPEAR) return "lance";
        if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION))
            return "throwable_potion";
        for (Category category : CATEGORIES) if (stack.is(category.tag)) return category.name;
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
