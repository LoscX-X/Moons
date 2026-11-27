package com.blanoir.tempos.client.access;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Cushions were introduced in 26.3. */
public final class CushionAccess {
    private CushionAccess() {}

    public static AABB boxAt(Vec3 position) {
        return new AABB(position, position);
    }

    public static Vec3 passengerOffset() {
        return Vec3.ZERO;
    }

    public static boolean supported() {
        return false;
    }

    public static boolean isItem(ItemStack stack) {
        return false;
    }

    public static boolean isCushion(Entity entity) {
        return false;
    }

    public static Vec3 placementPosition(Minecraft client, ItemStack stack, BlockHitResult hit) {
        return Vec3.ZERO;
    }

    public static boolean canPlace(Minecraft client, ItemStack stack, BlockHitResult hit) {
        return false;
    }
}
