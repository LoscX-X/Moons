package com.blanoir.moons.client.utils.world.placement;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.world.World;

/** Placement query over the real 1.8 item, block metadata, click face and player rotation. */
public final class LegacyPlacement {
    private LegacyPlacement() {}

    public record Context(EntityPlayer player, ItemStack stack, MovingObjectPosition hit) {
        public Context(EntityPlayer player, MovingObjectPosition hit) {
            this(player, player.getHeldItem(), hit);
        }

        public BlockPos getClickedPos() {
            BlockPos pos = hit.getBlockPos();
            return player.worldObj.getBlockState(pos).getBlock().isReplaceable(player.worldObj, pos)
                    ? pos
                    : pos.offset(hit.sideHit);
        }

        public boolean canPlace() {
            BlockPos pos = getClickedPos();
            return stack != null
                    && stack.stackSize > 0
                    && pos.getY() >= 0
                    && pos.getY() < 256
                    && player.worldObj.getWorldBorder().contains(pos)
                    && player.worldObj
                            .getBlockState(pos)
                            .getBlock()
                            .isReplaceable(player.worldObj, pos);
        }
    }

    public static IBlockState state(Block block, Context context) {
        if (!context.canPlace()) return null;
        BlockPos pos = context.getClickedPos();
        MovingObjectPosition hit = context.hit();
        if (!context.player()
                .worldObj
                .canBlockBePlaced(
                        block, pos, false, hit.sideHit, context.player(), context.stack()))
            return null;
        return block.onBlockPlaced(
                context.player().worldObj,
                pos,
                hit.sideHit,
                (float) (hit.hitVec.xCoord - hit.getBlockPos().getX()),
                (float) (hit.hitVec.yCoord - hit.getBlockPos().getY()),
                (float) (hit.hitVec.zCoord - hit.getBlockPos().getZ()),
                context.stack().getItem().getMetadata(context.stack().getMetadata()),
                context.player());
    }

    public static boolean survives(IBlockState state, World world, BlockPos pos) {
        return state.getBlock().canPlaceBlockAt(world, pos);
    }

    public static boolean unobstructed(
            World world, IBlockState state, BlockPos pos, EntityPlayer player) {
        for (AxisAlignedBB box :
                com.blanoir.moons.client.utils.world.LegacyWorld.collision(
                                state, world, pos, player)
                        .boxes())
            if (!world.checkNoEntityCollision(box.offset(pos.getX(), pos.getY(), pos.getZ())))
                return false;
        return true;
    }
}
