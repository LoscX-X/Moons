package com.blanoir.moons.client.module.impl.player.blockin;

import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Fixed-footprint shell geometry; every actual click is resolved against the current world. */
public final class BlockInPlanner {
    private static final double EPSILON = 1.0E-5;
    private static final Direction[] SUPPORTS = {
        Direction.DOWN,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.UP
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

        public boolean contains(AABB player) {
            return player.minX >= minX - .05
                    && player.maxX <= maxX + 1.05
                    && player.minZ >= minZ - .05
                    && player.maxZ <= maxZ + 1.05
                    && player.minY >= feetY - .5
                    && player.minY <= feetY + 1.5;
        }
    }

    public record Plan(BlockPos position, BlockHitResult hit) {}

    private BlockInPlanner() {}

    public static Layout layout(AABB player, boolean fillFloor, boolean roof) {
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
        BlockState state = client.level.getBlockState(pos);
        return !state.canBeReplaced()
                && Block.isShapeFullBlock(state.getCollisionShape(client.level, pos));
    }

    public static boolean validMaterial(Minecraft client, ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem item)
                || item.getBlock() instanceof FallingBlock
                || item.getBlock() instanceof TntBlock) return false;
        BlockState state = item.getBlock().defaultBlockState();
        return !state.canBeReplaced()
                && Block.isShapeFullBlock(state.getCollisionShape(client.level, BlockPos.ZERO));
    }

    static int materialSlot(Minecraft client, List<Identifier> priorities) {
        var inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (priorities.isEmpty()) {
            if (validMaterial(client, inventory.getItem(selected))) return selected;
            for (int slot = 0; slot < 9; slot++)
                if (validMaterial(client, inventory.getItem(slot))) return slot;
        } else {
            for (Identifier id : priorities) {
                if (validMaterial(client, inventory.getItem(selected))
                        && BuiltInRegistries.ITEM
                                .getKey(inventory.getItem(selected).getItem())
                                .equals(id)) return selected;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (validMaterial(client, stack)
                            && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id))
                        return slot;
                }
            }
        }
        return -1;
    }

    static Plan next(Minecraft client, Layout layout, Predicate<BlockPos> available) {
        Vec3 eye = client.player.getEyePosition();
        for (BlockPos pos : layout.targets()) {
            if (!available.test(pos) || solid(client, pos)) continue;
            // Prepare all roof anchors before covering any occupied cell. A
            // partial ceiling would prevent the remaining support jumps.
            if (layout.roof().contains(pos) && needsCap(client, layout)) continue;
            BlockHitResult hit = hit(client, pos, eye);
            if (hit != null) return new Plan(pos, hit);
        }
        // A missing bottom row may require an adjacent foundation block first.
        Set<BlockPos> foundations = new LinkedHashSet<>();
        for (BlockPos wall : layout.walls()) {
            if (wall.getY() == layout.feetY() && !solid(client, wall))
                foundations.add(wall.below());
        }
        for (BlockPos pos : foundations) {
            if (!available.test(pos)) continue;
            BlockHitResult hit = hit(client, pos, eye);
            if (hit != null) return new Plan(pos, hit);
        }
        if (needsCap(client, layout)) {
            for (BlockPos pos : layout.caps()) {
                if (!available.test(pos)) continue;
                BlockHitResult hit = hit(client, pos, eye);
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
                || !client.player.onGround()
                || client.player.isInWater()
                || client.player.isInLava()
                || !client.level.noCollision(
                        client.player, client.player.getBoundingBox().expandTowards(0, 1.25, 0)))
            return false;
        Vec3 raisedEye = client.player.getEyePosition().add(0, 1.0, 0);
        return layout.caps().stream()
                .anyMatch(pos -> available.test(pos) && hit(client, pos, raisedEye) != null);
    }

    static BlockHitResult hit(Minecraft client, BlockPos pos, Vec3 eye) {
        if (!client.level.hasChunkAt(pos)
                || !client.level.getWorldBorder().isWithinBounds(pos)
                || client.level.isOutsideBuildHeight(pos)
                || !client.level.getBlockState(pos).canBeReplaced()
                || client.player.getBoundingBox().intersects(new AABB(pos))
                || !client.level.isUnobstructed(
                        null, net.minecraft.world.phys.shapes.Shapes.create(new AABB(pos))))
            return null;
        double reach = client.player.blockInteractionRange();
        for (Direction direction : SUPPORTS) {
            BlockPos support = pos.relative(direction);
            if (!BlockPlacementUtils.solidWithoutMenu(client, support)) continue;
            Direction face = direction.getOpposite();
            for (double u : SAMPLES)
                for (double v : SAMPLES) {
                    Vec3 point = BlockPlacementUtils.facePoint(client, support, face, u, v);
                    if (!BlockPlacementUtils.withinReach(eye, point, reach)) continue;
                    BlockHitResult hit =
                            BlockPlacementUtils.visibleFaceHit(
                                    client, eye, support, face, point, EPSILON);
                    if (hit != null) return hit;
                }
        }
        return null;
    }

    static boolean validate(Minecraft client, Plan plan, BlockHitResult hit) {
        ItemStack stack = client.player.getMainHandItem();
        if (!validMaterial(client, stack)
                || !client.level.getBlockState(plan.position()).canBeReplaced()
                || !hit.getBlockPos().relative(hit.getDirection()).equals(plan.position())
                || client.player.getBoundingBox().intersects(new AABB(plan.position())))
            return false;
        BlockPlaceContext context =
                new BlockPlaceContext(
                        new UseOnContext(client.player, InteractionHand.MAIN_HAND, hit));
        BlockState state = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(context);
        return context.canPlace()
                && context.getClickedPos().equals(plan.position())
                && state != null
                && Block.isShapeFullBlock(state.getCollisionShape(client.level, plan.position()))
                && state.canSurvive(client.level, plan.position())
                && client.level.isUnobstructed(
                        state, plan.position(), CollisionContext.of(client.player));
    }
}
