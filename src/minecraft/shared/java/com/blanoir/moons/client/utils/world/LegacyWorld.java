package com.blanoir.moons.client.utils.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.*;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.*;

/** Geometry and fluid access backed by vanilla 1.8.9 blocks, including non-cube collision boxes. */
public final class LegacyWorld {
    private LegacyWorld() {}

    public static Vec3 collide(Entity entity, Vec3 movement, AxisAlignedBB box, World world) {
        double x = movement.xCoord, y = movement.yCoord, z = movement.zCoord;
        List<AxisAlignedBB> collisions =
                world.getCollidingBoundingBoxes(entity, box.addCoord(x, y, z));
        for (AxisAlignedBB obstacle : collisions) y = obstacle.calculateYOffset(box, y);
        box = box.offset(0, y, 0);
        for (AxisAlignedBB obstacle : collisions) x = obstacle.calculateXOffset(box, x);
        box = box.offset(x, 0, 0);
        for (AxisAlignedBB obstacle : collisions) z = obstacle.calculateZOffset(box, z);
        return new Vec3(x, y, z);
    }

    public static MovingObjectPosition hit(Vec3 at, EnumFacing face, BlockPos pos) {
        return new MovingObjectPosition(at, face, pos);
    }

    public static MovingObjectPosition hit(Vec3 at, EnumFacing face, BlockPos pos, boolean inside) {
        return hit(at, face, pos);
    }

    public static MovingObjectPosition hit(Entity entity, Vec3 at) {
        return new MovingObjectPosition(entity, at);
    }

    public static AxisAlignedBB box(
            double x, double y, double z, double maxX, double maxY, double maxZ) {
        return new AxisAlignedBB(x, y, z, maxX, maxY, maxZ);
    }

    public static AxisAlignedBB box(BlockPos pos) {
        return box(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    public static AxisAlignedBB box(BlockPos a, BlockPos b) {
        return new AxisAlignedBB(a, b);
    }

    public static AxisAlignedBB box(Vec3 a, Vec3 b) {
        return box(
                Math.min(a.xCoord, b.xCoord),
                Math.min(a.yCoord, b.yCoord),
                Math.min(a.zCoord, b.zCoord),
                Math.max(a.xCoord, b.xCoord),
                Math.max(a.yCoord, b.yCoord),
                Math.max(a.zCoord, b.zCoord));
    }

    public static AxisAlignedBB inflate(AxisAlignedBB b, double a) {
        return b.expand(a, a, a);
    }

    public static AxisAlignedBB inflate(AxisAlignedBB b, double x, double y, double z) {
        return b.expand(x, y, z);
    }

    public static AxisAlignedBB move(AxisAlignedBB b, double x, double y, double z) {
        return b.offset(x, y, z);
    }

    public static AxisAlignedBB move(AxisAlignedBB b, BlockPos p) {
        return b.offset(p.getX(), p.getY(), p.getZ());
    }

    public static AxisAlignedBB move(AxisAlignedBB b, Vec3 p) {
        return b.offset(p.xCoord, p.yCoord, p.zCoord);
    }

    public static java.util.Optional<Vec3> intercept(AxisAlignedBB b, Vec3 eye, Vec3 end) {
        MovingObjectPosition hit = b.calculateIntercept(eye, end);
        return java.util.Optional.ofNullable(hit == null ? null : hit.hitVec);
    }

    public static double distanceSquared(AxisAlignedBB b, Vec3 p) {
        double x = Math.max(b.minX, Math.min(b.maxX, p.xCoord)) - p.xCoord,
                y = Math.max(b.minY, Math.min(b.maxY, p.yCoord)) - p.yCoord,
                z = Math.max(b.minZ, Math.min(b.maxZ, p.zCoord)) - p.zCoord;
        return x * x + y * y + z * z;
    }

    public static boolean air(IBlockState state) {
        return state.getBlock().getMaterial() == Material.air;
    }

    public static boolean replaceable(IBlockState state) {
        return state.getBlock().getMaterial().isReplaceable();
    }

    public static FluidState fluid(IBlockState state) {
        return new FluidState(state);
    }

    public static FluidState fluid(IBlockAccess world, BlockPos pos) {
        return fluid(world.getBlockState(pos));
    }

    public static Shape collision(IBlockState state, IBlockAccess access, BlockPos pos) {
        return collision(state, access, pos, null);
    }

    public static Shape collision(
            IBlockState state, IBlockAccess access, BlockPos pos, Entity entity) {
        World world = access instanceof World w ? w : Minecraft.getMinecraft().theWorld;
        if (world == null)
            throw new IllegalStateException("Collision query requires an active world");
        var boxes = new ArrayList<AxisAlignedBB>();
        Block block = state.getBlock();
        IBlockAccess query =
                access.getBlockState(pos) == state ? access : new StateView(access, pos, state);
        block.setBlockBoundsBasedOnState(query, pos);
        if (query != access && block instanceof net.minecraft.block.BlockStairs stairs) {
            stairs.setBaseCollisionBounds(query, pos);
            boxes.add(bounds(block).offset(pos.getX(), pos.getY(), pos.getZ()));
            boolean corner = stairs.func_176306_h(query, pos);
            boxes.add(bounds(block).offset(pos.getX(), pos.getY(), pos.getZ()));
            if (corner && stairs.func_176304_i(query, pos))
                boxes.add(bounds(block).offset(pos.getX(), pos.getY(), pos.getZ()));
        } else if (query != access
                && (block instanceof net.minecraft.block.BlockSlab
                        || block instanceof net.minecraft.block.BlockDoor
                        || block instanceof net.minecraft.block.BlockTrapDoor)) {
            boxes.add(bounds(block).offset(pos.getX(), pos.getY(), pos.getZ()));
        } else
            block.addCollisionBoxesToList(
                    world,
                    pos,
                    state,
                    new AxisAlignedBB(
                            pos.getX() - 2,
                            pos.getY() - 2,
                            pos.getZ() - 2,
                            pos.getX() + 3,
                            pos.getY() + 3,
                            pos.getZ() + 3),
                    boxes,
                    entity);
        return new Shape(
                boxes.stream().map(b -> b.offset(-pos.getX(), -pos.getY(), -pos.getZ())).toList());
    }

    public static Shape outline(IBlockState state, IBlockAccess access, BlockPos pos) {
        Block block = state.getBlock();
        block.setBlockBoundsBasedOnState(
                access.getBlockState(pos) == state ? access : new StateView(access, pos, state),
                pos);
        return air(state)
                ? new Shape(List.of())
                : new Shape(
                        List.of(
                                new AxisAlignedBB(
                                        block.getBlockBoundsMinX(),
                                        block.getBlockBoundsMinY(),
                                        block.getBlockBoundsMinZ(),
                                        block.getBlockBoundsMaxX(),
                                        block.getBlockBoundsMaxY(),
                                        block.getBlockBoundsMaxZ())));
    }

    public static List<Shape> collisions(World world, Entity entity, AxisAlignedBB box) {
        return world.getCollidingBoundingBoxes(entity, box).stream()
                .map(b -> new Shape(List.of(b)))
                .toList();
    }

    public static boolean noCollision(World world, Entity entity, AxisAlignedBB box) {
        return world.getCollidingBoundingBoxes(entity, box).isEmpty();
    }

    public static boolean full(Shape shape) {
        if (shape.isEmpty()) return false;
        // Bounding the union alone incorrectly calls stairs and crossing fences a full cube.
        var xs = new TreeSet<Double>();
        var ys = new TreeSet<Double>();
        var zs = new TreeSet<Double>();
        xs.add(0D);
        xs.add(1D);
        ys.add(0D);
        ys.add(1D);
        zs.add(0D);
        zs.add(1D);
        for (AxisAlignedBB b : shape.boxes()) {
            addBoundary(xs, b.minX);
            addBoundary(xs, b.maxX);
            addBoundary(ys, b.minY);
            addBoundary(ys, b.maxY);
            addBoundary(zs, b.minZ);
            addBoundary(zs, b.maxZ);
        }
        Double[] x = xs.toArray(Double[]::new),
                y = ys.toArray(Double[]::new),
                z = zs.toArray(Double[]::new);
        for (int i = 1; i < x.length; i++)
            for (int j = 1; j < y.length; j++)
                for (int k = 1; k < z.length; k++) {
                    Vec3 center =
                            new Vec3(
                                    (x[i - 1] + x[i]) / 2,
                                    (y[j - 1] + y[j]) / 2,
                                    (z[k - 1] + z[k]) / 2);
                    if (shape.boxes().stream().noneMatch(b -> b.isVecInside(center))) return false;
                }
        return true;
    }

    private static void addBoundary(Set<Double> values, double value) {
        if (value > 0 && value < 1) values.add(value);
    }

    private static AxisAlignedBB bounds(Block b) {
        return new AxisAlignedBB(
                b.getBlockBoundsMinX(),
                b.getBlockBoundsMinY(),
                b.getBlockBoundsMinZ(),
                b.getBlockBoundsMaxX(),
                b.getBlockBoundsMaxY(),
                b.getBlockBoundsMaxZ());
    }

    private record StateView(IBlockAccess delegate, BlockPos position, IBlockState state)
            implements IBlockAccess {
        public IBlockState getBlockState(BlockPos pos) {
            return pos.equals(position) ? state : delegate.getBlockState(pos);
        }

        public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) {
            return delegate.getTileEntity(pos);
        }

        public int getCombinedLight(BlockPos pos, int light) {
            return delegate.getCombinedLight(pos, light);
        }

        public boolean isAirBlock(BlockPos pos) {
            return air(getBlockState(pos));
        }

        public net.minecraft.world.biome.BiomeGenBase getBiomeGenForCoords(BlockPos pos) {
            return delegate.getBiomeGenForCoords(pos);
        }

        public boolean extendedLevelsInChunkCache() {
            return delegate.extendedLevelsInChunkCache();
        }

        public int getStrongPower(BlockPos pos, EnumFacing facing) {
            return delegate.getStrongPower(pos, facing);
        }

        public net.minecraft.world.WorldType getWorldType() {
            return delegate.getWorldType();
        }
    }

    public static MovingObjectPosition clip(IBlockAccess access, LegacyRay context) {
        if (!(access instanceof World world))
            throw new IllegalArgumentException("Ray trace requires World");
        MovingObjectPosition hit =
                world.rayTraceBlocks(
                        context.getFrom(),
                        context.getTo(),
                        context.fluid() != LegacyRay.Fluid.NONE,
                        context.block() == LegacyRay.Block.COLLIDER,
                        true);
        return hit == null
                ? miss(context.getTo(), EnumFacing.UP, new BlockPos(context.getTo()))
                : hit;
    }

    public static MovingObjectPosition miss(Vec3 end, EnumFacing face, BlockPos pos) {
        return new MovingObjectPosition(MovingObjectPosition.MovingObjectType.MISS, end, face, pos);
    }

    public record FluidState(IBlockState state) {
        public boolean is(Material material) {
            return state.getBlock().getMaterial() == material;
        }

        public boolean isSource() {
            return state.getBlock() instanceof BlockLiquid
                    && state.getValue(BlockLiquid.LEVEL) == 0;
        }

        public boolean isEmpty() {
            return !state.getBlock().getMaterial().isLiquid();
        }

        public Material getType() {
            return state.getBlock().getMaterial();
        }
    }

    public record Shape(List<AxisAlignedBB> boxes) {
        public Shape {
            boxes = List.copyOf(boxes);
        }

        public boolean isEmpty() {
            return boxes.isEmpty();
        }

        public List<AxisAlignedBB> toAabbs() {
            return boxes;
        }

        public AxisAlignedBB bounds() {
            if (isEmpty()) throw new IllegalStateException("Empty shape");
            AxisAlignedBB out = boxes.get(0);
            for (int i = 1; i < boxes.size(); i++) out = out.union(boxes.get(i));
            return out;
        }

        public double max(EnumFacing.Axis axis) {
            AxisAlignedBB b = bounds();
            return switch (axis) {
                case X -> b.maxX;
                case Y -> b.maxY;
                case Z -> b.maxZ;
            };
        }

        public double min(EnumFacing.Axis axis) {
            AxisAlignedBB b = bounds();
            return switch (axis) {
                case X -> b.minX;
                case Y -> b.minY;
                case Z -> b.minZ;
            };
        }

        public MovingObjectPosition clip(Vec3 eye, Vec3 end, BlockPos pos) {
            MovingObjectPosition best = null;
            for (AxisAlignedBB b : boxes) {
                MovingObjectPosition hit =
                        b.offset(pos.getX(), pos.getY(), pos.getZ()).calculateIntercept(eye, end);
                if (hit != null
                        && (best == null
                                || eye.squareDistanceTo(hit.hitVec)
                                        < eye.squareDistanceTo(best.hitVec)))
                    best = new MovingObjectPosition(hit.hitVec, hit.sideHit, pos);
            }
            return best;
        }
    }
}
