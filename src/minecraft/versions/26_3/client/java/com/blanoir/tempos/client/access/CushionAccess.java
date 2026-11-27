package com.blanoir.tempos.client.access;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.CushionItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Native cushion rules are version-owned; older game versions never link cushion classes. */
public final class CushionAccess {
    private CushionAccess() {}

    public static AABB boxAt(Vec3 position) {
        return EntityTypes.CUSHION.getSpawnAABB(position);
    }

    public static Vec3 passengerOffset() {
        return EntityTypes.CUSHION
                .getDimensions()
                .attachments()
                .getClamped(EntityAttachment.PASSENGER, 0, 0);
    }

    public static boolean supported() {
        return true;
    }

    public static boolean isItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CushionItem;
    }

    public static boolean isCushion(Entity entity) {
        return entity instanceof Cushion;
    }

    public static Vec3 placementPosition(Minecraft client, ItemStack stack, BlockHitResult hit) {
        var context = new BlockPlaceContext(client.player, InteractionHand.MAIN_HAND, stack, hit);
        return Vec3.atCenterOfWithY(context.getClickedPos(), hit.getLocation().y);
    }

    public static boolean canPlace(Minecraft client, ItemStack stack, BlockHitResult hit) {
        // These blocks recalculate the native collision ray; exclude them from this outline
        // planner.
        if (client.level
                .getBlockState(hit.getBlockPos())
                .is(BlockTags.CUSHION_USES_COLLISION_SHAPE)) return false;
        var box = boxAt(placementPosition(client, stack, hit));
        return Cushion.canBePlacedAt(client.level, box)
                && client.level.getEntitiesOfClass(Cushion.class, box).isEmpty();
    }
}
