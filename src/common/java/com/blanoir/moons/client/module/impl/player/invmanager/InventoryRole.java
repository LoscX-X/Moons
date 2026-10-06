package com.blanoir.moons.client.module.impl.player.invmanager;

import com.blanoir.moons.client.utils.inventory.LegacyItems;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.Locale;

/** A slot has one role; multiple slots may deliberately request the same role. */
public enum InventoryRole {
    FREE("Free", null),
    LOCKED("Locked", net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.barrier)),
    CUSTOM(
            "Custom items",
            net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.chest)),
    SWORD("Sword", Items.iron_sword),
    PICKAXE("Plain pickaxe", Items.iron_pickaxe),
    SILK_PICKAXE("Silk pickaxe", Items.diamond_pickaxe),
    FORTUNE_PICKAXE("Fortune pickaxe", Items.diamond_pickaxe),
    AXE("Axe", Items.iron_axe),
    SHOVEL("Shovel", Items.iron_shovel),
    BOW("Bow", Items.bow),
    BLOCK(
            "Blocks",
            net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.cobblestone)),
    FOOD("Food", Items.cooked_beef),
    GOLDEN_APPLE("Golden apple", Items.golden_apple),
    PEARL("Pearls", Items.ender_pearl),
    THROWABLE("Throwables", Items.snowball),
    WATER("Water", Items.water_bucket),
    LAVA("Lava", Items.lava_bucket);

    private final String label;
    private final Item icon;

    InventoryRole(String label, Item icon) {
        this.label = label;
        this.icon = icon;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return label;
    }

    public ItemStack icon() {
        return icon == null ? LegacyItems.EMPTY : new ItemStack(icon);
    }

    public boolean managed() {
        return this != FREE && this != LOCKED;
    }

    public static InventoryRole parse(String value) {
        for (InventoryRole role : values()) if (role.id().equals(value)) return role;
        return FREE;
    }
}
