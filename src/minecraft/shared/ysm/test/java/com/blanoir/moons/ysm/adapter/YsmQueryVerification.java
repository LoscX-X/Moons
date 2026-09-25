package com.blanoir.moons.ysm.adapter;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;

import java.util.*;

/** Executes adapter queries with real registries/components, without a client, world or GPU. */
public final class YsmQueryVerification {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (var item : List.of(Items.STONE, Items.SHIELD, Items.TIPPED_ARROW, Items.ARROW))
            item.builtInRegistryHolder()
                    .bindComponents(
                            DataComponentMap.builder()
                                    .set(DataComponents.MAX_STACK_SIZE, 64)
                                    .build());
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var entity = (EquippedStand) unsafe.allocateInstance(EquippedStand.class);
        verifyEquipmentSampling(entity);
        entity.items =
                Map.of(
                        EquipmentSlot.MAINHAND,
                        Items.STONE.getDefaultInstance(),
                        EquipmentSlot.OFFHAND,
                        Items.SHIELD.getDefaultInstance());
        entity.effects = List.of(new MobEffectInstance(MobEffects.POISON, 200, 2));
        equal(
                "stone",
                query(entity, "get_equipped_item_name", "main_hand"),
                "Main hand short name");
        equal("shield", query(entity, "get_equipped_item_name", "off_hand"), "Bedrock off_hand");
        equal(true, query(entity, "is_item_name_any", "mainhand", "stone"), "Default namespace");
        equal(true, query(entity, "is_item_name_any", "MAINHAND", "minecraft:stone"), "Slot case");
        equal(
                false,
                query(entity, "is_item_name_any", "mainhand", "other:stone"),
                "Foreign namespace");
        equal(null, query(entity, "is_item_name_any", "invalid", "stone"), "Invalid slot");
        equal(
                null,
                query(entity, "is_item_name_any", "mainhand", "invalid name", "stone"),
                "Invalid ID does not match later candidates");
        equal(
                true,
                query(entity, "is_item_name_any", "mainhand", "stone", "invalid name"),
                "Successful match short circuits");
        equal(
                null,
                query(entity, "equipped_item_any_tag", "mainhand", "invalid tag"),
                "Invalid tag is null");
        equal(null, query(entity, "remaining_durability"), "Missing durability argument");
        equal(null, query(entity, "max_durability", "invalid"), "Invalid durability slot");
        equal(null, query(entity, "effect_level"), "Missing effect argument");
        equal(3, query(entity, "effect_level", "poison"), "Living entity amplifier");
        entity.items = Map.of();
        equal("empty", query(entity, "get_equipped_item_name", "main_hand"), "Empty hand name");
        equal(
                false,
                query(entity, "is_item_name_any", "mainhand", "air"),
                "Empty hand is not an item match");

        var arrow = (PotionArrow) unsafe.allocateInstance(PotionArrow.class);
        arrow.pickup = Items.ARROW.getDefaultInstance();
        equal(0, query(arrow, "effect_level", "poison"), "Ordinary arrow");
        arrow.pickup = Items.TIPPED_ARROW.getDefaultInstance();
        arrow.pickup.set(
                DataComponents.POTION_CONTENTS,
                new PotionContents(Potions.STRONG_POISON)
                        .withEffectAdded(new MobEffectInstance(MobEffects.SLOWNESS, 200, 3)));
        equal(2, query(arrow, "effect_level", "poison"), "Potion arrow base effect");
        equal(4, query(arrow, "effect_level", "minecraft:slowness"), "Potion arrow custom effect");
        equal(6, query(arrow, "effect_level", "poison", "slowness"), "Summed potion effects");
        equal(
                0,
                query(arrow, "effect_level", "speed", "invalid effect"),
                "Absent or invalid effect");
        var raft = (PaddleRaft) unsafe.allocateInstance(PaddleRaft.class);
        Map<String, Object> boat = new HashMap<>();
        YsmAdditionalObservations.boat(boat, raft, .5f);
        equal(true, boat.get("boat_is_chest"), "Chest boat detection");
        equal(true, boat.get("boat_is_raft"), "Raft detection");
        equal(true, boat.get("boat_left_paddle"), "Boat itself supplies paddle state");
        equal(false, boat.get("boat_right_paddle"), "Independent paddles");
        equal(-.5f, boat.get("boat_left_rowing_time"), "Interpolated rowing direction");
        equal(-1.5f, boat.get("boat_right_rowing_time"), "Right rowing direction");
        equal(-2.4f, boat.get("boat_body_offset_z"), "Chest body offset");
        equal(2.4f, boat.get("boat_chest_passenger_offset"), "Chest passenger offset");
        System.out.println(
                "YSM_QUERIES_VERIFIED equipment=names+hands+empty identifiers=qualified+default effects=living+normal-arrow+potion-arrow boat=raft+paddles+offsets");
    }

    private static void verifyEquipmentSampling(EquippedStand entity) throws Exception {
        var holder = Items.STONE.builtInRegistryHolder();
        var originalTags = holder.tags().toList();
        var sword =
                net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.ITEM,
                        net.minecraft.resources.Identifier.withDefaultNamespace("swords"));
        var axe =
                net.minecraft.tags.TagKey.create(
                        net.minecraft.core.registries.Registries.ITEM,
                        net.minecraft.resources.Identifier.withDefaultNamespace("axes"));
        var bindTags = holder.getClass().getDeclaredMethod("bindTags", Collection.class);
        bindTags.setAccessible(true);
        try {
            Random random = new Random(20260925);
            for (int sample = 0; sample < 128; sample++) {
                // A reload can change tags without replacing the item or stack instance.
                bindTags.invoke(
                        holder,
                        sample % 3 == 0
                                ? List.of(axe, sword)
                                : sample % 3 == 1 ? List.of(axe) : List.of());
                var items = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    int choice = random.nextInt(3);
                    items.put(
                            slot,
                            choice == 0
                                    ? ItemStack.EMPTY
                                    : choice == 1
                                            ? Items.STONE.getDefaultInstance()
                                            : Items.SHIELD.getDefaultInstance());
                }
                entity.items = items;
                Map<String, Object> expected = new LinkedHashMap<>(),
                        actual = new LinkedHashMap<>();
                int count = 0;
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack stack = entity.getItemBySlot(slot);
                    String name = slot.getName();
                    expected.put("has_" + name, !stack.isEmpty());
                    expected.put(
                            name + "_item",
                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(stack.getItem())
                                    .toString());
                    expected.put(
                            name + "_category",
                            stack.isEmpty()
                                    ? "empty"
                                    : stack.is(sword)
                                            ? "sword"
                                            : stack.is(axe)
                                                    ? "axe"
                                                    : stack.is(Items.STONE) ? "stone" : "shield");
                    expected.put(
                            name + "_use", stack.getUseAnimation().name().toLowerCase(Locale.ROOT));
                    expected.put(
                            name + "_tags",
                            stack.getItem()
                                    .builtInRegistryHolder()
                                    .tags()
                                    .map(t -> t.location().toString())
                                    .toList());
                    if (slot.isArmor() && !stack.isEmpty()) count++;
                }
                equal(count, YsmEquipmentObservations.sample(actual, entity), "Equipment count");
                equal(expected, actual, "Equipment fields and live tags");
                equal(
                        new ArrayList<>(expected.keySet()),
                        new ArrayList<>(actual.keySet()),
                        "Equipment encounter order");
                var retained = new LinkedHashMap<>(actual);
                YsmEquipmentObservations.sample(new HashMap<>(), entity);
                equal(retained, actual, "Retained observation snapshot");
            }
        } finally {
            bindTags.invoke(holder, originalTags);
        }
        System.out.println(
                "YSM_EQUIPMENT_SAMPLING_VERIFIED cases=128 order+count+fields=identical tags=live category-priority=preserved");
    }

    private static Object query(Entity entity, String name, Object... args) {
        return YsmEntityQueries.query(
                entity,
                Map.of(),
                message -> {
                    throw new AssertionError("Unexpected diagnostic: " + message);
                },
                "ysm",
                name,
                Arrays.asList(args));
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class EquippedStand extends ArmorStand {
        Map<EquipmentSlot, ItemStack> items;
        Collection<MobEffectInstance> effects;

        EquippedStand() {
            super((Level) null, 0, 0, 0);
        }

        @Override
        public ItemStack getItemBySlot(EquipmentSlot slot) {
            return items.getOrDefault(slot, ItemStack.EMPTY);
        }

        @Override
        public Collection<MobEffectInstance> getActiveEffects() {
            return effects;
        }
    }

    private static final class PaddleRaft
            extends net.minecraft.world.entity.vehicle.boat.ChestRaft {
        PaddleRaft() {
            super(null, null, () -> Items.BAMBOO_CHEST_RAFT);
        }

        @Override
        public boolean getPaddleState(int side) {
            return side == 0;
        }

        @Override
        public float getRowingTime(int side, float partial) {
            return side + partial;
        }
    }

    private static final class PotionArrow extends Arrow {
        ItemStack pickup;

        PotionArrow() {
            super(null, null);
        }

        @Override
        public ItemStack getPickupItemStackOrigin() {
            return pickup;
        }
    }
}
