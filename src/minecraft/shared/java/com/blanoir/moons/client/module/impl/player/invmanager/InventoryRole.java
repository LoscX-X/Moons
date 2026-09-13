package com.blanoir.moons.client.module.impl.player.invmanager;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** A slot has one role; multiple slots may deliberately request the same role. */
public enum InventoryRole {
    FREE("Free", Items.AIR),
    LOCKED("Locked", Items.BARRIER),
    CUSTOM("Custom items", Items.CHEST),
    SWORD("Sword", Items.IRON_SWORD),
    PICKAXE("Plain pickaxe", Items.IRON_PICKAXE),
    SILK_PICKAXE("Silk pickaxe", Items.DIAMOND_PICKAXE),
    FORTUNE_PICKAXE("Fortune pickaxe", Items.DIAMOND_PICKAXE),
    AXE("Axe", Items.IRON_AXE),
    SHOVEL("Shovel", Items.IRON_SHOVEL),
    BOW("Bow", Items.BOW),
    CROSSBOW("Crossbow", Items.CROSSBOW),
    BLOCK("Blocks", Items.COBBLESTONE),
    FOOD("Food", Items.COOKED_BEEF),
    GOLDEN_APPLE("Golden apple", Items.GOLDEN_APPLE),
    PEARL("Pearls", Items.ENDER_PEARL),
    THROWABLE("Throwables", Items.SNOWBALL),
    WATER("Water", Items.WATER_BUCKET),
    LAVA("Lava", Items.LAVA_BUCKET),
    SHIELD("Shield", Items.SHIELD),
    TOTEM("Totem", Items.TOTEM_OF_UNDYING);

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
        return icon == Items.AIR ? ItemStack.EMPTY : new ItemStack(icon);
    }

    public boolean managed() {
        return this != FREE && this != LOCKED;
    }

    public static InventoryRole parse(String value) {
        for (InventoryRole role : values()) if (role.id().equals(value)) return role;
        return FREE;
    }
}
