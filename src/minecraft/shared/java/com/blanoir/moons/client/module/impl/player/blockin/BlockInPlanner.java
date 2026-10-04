package com.blanoir.moons.client.module.impl.player.blockin;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.world.LegacyWorld;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.LegacyPlacement;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockTNT;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Fixed-footprint shell geometry; every actual click is resolved against the current world. */
public final class BlockInPlanner {
    private static final PlacementRaycast RAYS = new PlacementRaycast("blockin");
    private static final double EPSILON = 1.0E-5;
    private static final EnumFacing[] SUPPORTS = {
        EnumFacing.DOWN,
        EnumFacing.NORTH,
        EnumFacing.SOUTH,
        EnumFacing.WEST,
        EnumFacing.EAST,
        EnumFacing.UP
    };
    private static final double[] SAMPLES = {.5, .2, .8};

    public record Layout(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int feetY,
            List<BlockPos> floor,
            List<BlockPos> walls,
            List<BlockPos> roof,
            List<BlockPos> caps) {
        public List<BlockPos> targets() {
            var result = new ArrayList<BlockPos>(floor);
            result.addAll(walls);
            result.addAll(roof);
            return result;
        }

        public boolean contains(AxisAlignedBB player) {
            return player.minX >= minX - .05
                    && player.maxX <= maxX + 1.05
                    && player.minZ >= minZ - .05
                    && player.maxZ <= maxZ + 1.05
                    && player.minY >= feetY - .5
                    && player.minY <= feetY + 1.5;
        }
    }

    public record Plan(BlockPos position, MovingObjectPosition hit) {}

    private BlockInPlanner() {}

    public static Layout layout(AxisAlignedBB player, boolean fillFloor, boolean roof) {
        int minX = Mth.floor(player.minX + EPSILON), maxX = Mth.floor(player.maxX - EPSILON);
        int minZ = Mth.floor(player.minZ + EPSILON), maxZ = Mth.floor(player.maxZ - EPSILON);
        int y = Mth.floor(player.minY + EPSILON);
        var floor = new ArrayList<BlockPos>();
        var walls = new ArrayList<BlockPos>();
        var ceiling = new ArrayList<BlockPos>();
        var caps = new ArrayList<BlockPos>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (fillFloor) floor.add(new BlockPos(x, y - 1, z));
                if (roof) {
                    ceiling.add(new BlockPos(x, y + 2, z));
                    caps.add(new BlockPos(x, y + 2, z == minZ ? minZ - 1 : maxZ + 1));
                }
            }
        }
        for (int layer = 0; layer < 2; layer++) {
            for (int x = minX; x <= maxX; x++) {
                walls.add(new BlockPos(x, y + layer, minZ - 1));
                walls.add(new BlockPos(x, y + layer, maxZ + 1));
            }
            for (int z = minZ; z <= maxZ; z++) {
                walls.add(new BlockPos(minX - 1, y + layer, z));
                walls.add(new BlockPos(maxX + 1, y + layer, z));
            }
        }
        return new Layout(
                minX,
                maxX,
                minZ,
                maxZ,
                y,
                List.copyOf(floor),
                List.copyOf(walls),
                List.copyOf(ceiling),
                List.copyOf(caps));
    }

    public static boolean solid(Minecraft client, BlockPos pos) {
        IBlockState state = client.theWorld.getBlockState(pos);
        return !LegacyWorld.replaceable(state)
                && LegacyWorld.full(LegacyWorld.collision(state, client.theWorld, pos));
    }

    public static boolean validMaterial(Minecraft client, ItemStack stack) {
        if (LegacyItems.empty(stack)
                || !(stack.getItem() instanceof ItemBlock item)
                || item.getBlock() instanceof BlockFalling
                || item.getBlock() instanceof BlockTNT) return false;
        IBlockState state = item.getBlock().getDefaultState();
        return !LegacyWorld.replaceable(state)
                && LegacyWorld.full(LegacyWorld.collision(state, client.theWorld, BlockPos.ORIGIN));
    }

    static int materialSlot(Minecraft client, List<ResourceLocation> priorities) {
        var inventory = client.thePlayer.inventory;
        int selected = inventory.currentItem;
        if (priorities.isEmpty()) {
            if (validMaterial(client, inventory.getStackInSlot(selected))) return selected;
            for (int slot = 0; slot < 9; slot++)
                if (validMaterial(client, inventory.getStackInSlot(slot))) return slot;
        } else {
            for (ResourceLocation id : priorities) {
                if (validMaterial(client, inventory.getStackInSlot(selected))
                        && Item.itemRegistry
                                .getNameForObject(inventory.getStackInSlot(selected).getItem())
                                .equals(id)) return selected;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = inventory.getStackInSlot(slot);
                    if (validMaterial(client, stack)
                            && Item.itemRegistry.getNameForObject(stack.getItem()).equals(id))
                        return slot;
                }
            }
        }
        return -1;
    }

    static Plan next(Minecraft client, Layout layout, Predicate<BlockPos> available) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        for (BlockPos pos : layout.targets()) {
            if (!available.test(pos) || solid(client, pos)) continue;
            // Prepare all roof anchors before covering any occupied cell. A
            // partial ceiling would prevent the remaining support jumps.
            if (layout.roof().contains(pos) && needsCap(client, layout)) continue;
            MovingObjectPosition hit = hit(client, pos, eye);
            if (hit != null) return new Plan(pos, hit);
        }
        // A missing bottom row may require an adjacent foundation block first.
        Set<BlockPos> foundations = new LinkedHashSet<>();
        for (BlockPos wall : layout.walls()) {
            if (wall.getY() == layout.feetY() && !solid(client, wall)) foundations.add(wall.down());
        }
        for (BlockPos pos : foundations) {
            if (!available.test(pos)) continue;
            MovingObjectPosition hit = hit(client, pos, eye);
            if (hit != null) return new Plan(pos, hit);
        }
        if (needsCap(client, layout)) {
            for (BlockPos pos : layout.caps()) {
                if (!available.test(pos)) continue;
                MovingObjectPosition hit = hit(client, pos, eye);
                if (hit != null) return new Plan(pos, hit);
            }
        }
        return null;
    }

    static boolean needsCap(Minecraft client, Layout layout) {
        for (int i = 0; i < layout.roof().size(); i++) {
            if (!solid(client, layout.roof().get(i)) && !solid(client, layout.caps().get(i)))
                return true;
        }
        return false;
    }

    static boolean canJumpForCap(Minecraft client, Layout layout, Predicate<BlockPos> available) {
        if (!needsCap(client, layout)
                || !client.thePlayer.onGround
                || client.thePlayer.isInWater()
                || client.thePlayer.isInLava()
                || !LegacyWorld.noCollision(
                        client.theWorld,
                        client.thePlayer,
                        client.thePlayer.getEntityBoundingBox().addCoord(0, 1.25, 0))) return false;
        Vec3 raisedEye = client.thePlayer.getPositionEyes(1.0F).addVector(0, 1.0, 0);
        return layout.caps().stream()
                .anyMatch(pos -> available.test(pos) && hit(client, pos, raisedEye) != null);
    }

    static MovingObjectPosition hit(Minecraft client, BlockPos pos, Vec3 eye) {
        if (!client.theWorld.isBlockLoaded(pos)
                || !client.theWorld.getWorldBorder().contains(pos)
                || (pos.getY() < 0 || pos.getY() >= 256)
                || !LegacyWorld.replaceable(client.theWorld.getBlockState(pos))
                || client.thePlayer.getEntityBoundingBox().intersectsWith(LegacyWorld.box(pos))
                || !client.theWorld.checkNoEntityCollision(LegacyWorld.box(pos))) return null;
        double reach = Minecraft.getMinecraft().playerController.getBlockReachDistance();
        for (EnumFacing direction : SUPPORTS) {
            BlockPos support = pos.offset(direction);
            if (!BlockPlacementUtils.solidWithoutMenu(client, support)) continue;
            EnumFacing face = direction.getOpposite();
            for (double u : SAMPLES)
                for (double v : SAMPLES) {
                    Vec3 point = BlockPlacementUtils.facePoint(client, support, face, u, v);
                    if (!BlockPlacementUtils.withinReach(eye, point, reach)) continue;
                    MovingObjectPosition hit =
                            RAYS.visibleFaceHit(client, eye, support, face, point, EPSILON);
                    if (hit != null) return hit;
                }
        }
        return null;
    }

    static boolean validate(Minecraft client, Plan plan, MovingObjectPosition hit) {
        ItemStack stack = client.thePlayer.getHeldItem();
        if (!validMaterial(client, stack)
                || !LegacyWorld.replaceable(client.theWorld.getBlockState(plan.position()))
                || !hit.getBlockPos().offset(hit.sideHit).equals(plan.position())
                || client.thePlayer
                        .getEntityBoundingBox()
                        .intersectsWith(LegacyWorld.box(plan.position()))) return false;
        LegacyPlacement.Context context = new LegacyPlacement.Context(client.thePlayer, hit);
        IBlockState state =
                LegacyPlacement.state(((ItemBlock) stack.getItem()).getBlock(), context);
        return context.canPlace()
                && context.getClickedPos().equals(plan.position())
                && state != null
                && LegacyWorld.full(LegacyWorld.collision(state, client.theWorld, plan.position()))
                && LegacyPlacement.survives(state, client.theWorld, plan.position())
                && LegacyPlacement.unobstructed(
                        client.theWorld, state, plan.position(), client.thePlayer);
    }
}
