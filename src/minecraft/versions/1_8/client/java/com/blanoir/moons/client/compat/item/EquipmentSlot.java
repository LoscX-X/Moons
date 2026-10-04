package com.blanoir.moons.client.compat.item;

/** The four real 1.8.9 armor inventory indices; there is no offhand slot. */
public enum EquipmentSlot {
    FEET(0),
    LEGS(1),
    CHEST(2),
    HEAD(3);
    private final int index;

    EquipmentSlot(int index) {
        this.index = index;
    }

    public int index() {
        return index;
    }
}
