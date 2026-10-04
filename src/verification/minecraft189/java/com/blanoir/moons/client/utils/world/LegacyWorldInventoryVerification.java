package com.blanoir.moons.client.utils.world;

import com.blanoir.moons.client.module.impl.player.invmanager.*;
import com.blanoir.moons.client.utils.inventory.LegacyItems;

import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.*;

/** Real 1.8 block/item regression cases that do not create a client or OpenGL context. */
public final class LegacyWorldInventoryVerification {
    public static void main(String[] arguments) {
        Bootstrap.register();
        check(LegacyWorld.full(shape(box(0, 0, 0, 1, 1, 1))), "solid cube");
        check(!LegacyWorld.full(shape(box(0, 0, 0, 1, .5, 1))), "half slab is not full");
        check(
                !LegacyWorld.full(shape(box(0, 0, 0, 1, .5, 1), box(.5, .5, 0, 1, 1, 1))),
                "stairs union bounds do not imply full coverage");
        check(
                LegacyWorld.full(shape(box(0, 0, 0, 1, .5, 1), box(0, .5, 0, 1, 1, 1))),
                "two complementary slabs cover a cube");
        check(
                !LegacyWorld.full(shape(box(0, 0, .4, 1, 1, .6), box(.4, 0, 0, .6, 1, 1))),
                "crossed panes leave uncovered corners");
        var view = new EmptyView();
        IBlockState top =
                Blocks.stone_slab
                        .getDefaultState()
                        .withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP);
        var bounds = LegacyWorld.outline(top, view, BlockPos.ORIGIN).bounds();
        check(
                bounds.minY == .5 && bounds.maxY == 1,
                "predicted slab uses its state even when actual block is air");
        LegacyRay ray =
                new LegacyRay(
                        new Vec3(-1, .5, .5),
                        new Vec3(2, .5, .5),
                        LegacyRay.Block.OUTLINE,
                        LegacyRay.Fluid.NONE,
                        null);
        check(
                ray.getBlockShape(Blocks.water.getDefaultState(), view, BlockPos.ORIGIN).isEmpty(),
                "fluid is never a solid placement target");
        check(
                ray.getFluidShape(
                                LegacyWorld.fluid(Blocks.water.getDefaultState()),
                                view,
                                BlockPos.ORIGIN)
                        .isEmpty(),
                "fluid NONE ignores source");
        ray = new LegacyRay(ray.from(), ray.to(), ray.block(), LegacyRay.Fluid.SOURCE_ONLY, null);
        check(
                !ray.getFluidShape(
                                LegacyWorld.fluid(Blocks.water.getDefaultState()),
                                view,
                                BlockPos.ORIGIN)
                        .isEmpty(),
                "source pickup remains targetable");
        check(
                LegacyItems.empty(null) && LegacyItems.empty(LegacyItems.EMPTY),
                "null and snapshot empty normalize");
        ItemStack original = new ItemStack(Items.diamond_sword);
        check(
                InventoryItems.quality(InventoryRole.SWORD, original) == 8,
                "1.8 diamond sword modifier plus player base damage");
        original.addEnchantment(Enchantment.sharpness, 4);
        check(
                InventoryItems.quality(InventoryRole.SWORD, original) == 13,
                "1.8 sharpness adds 1.25 per level");
        ItemStack copy = LegacyItems.copy(original);
        copy.stackSize = 2;
        check(
                original.stackSize == 1
                        && !LegacyItems.matches(original, copy)
                        && LegacyItems.same(original, copy),
                "snapshot copies and count-aware before-state guards");
        ItemStack a = new ItemStack(Blocks.wool, 1, 1), b = new ItemStack(Blocks.wool, 1, 2);
        check(!LegacyItems.same(a, b), "metadata variants cannot merge");
        var items = new ArrayList<ItemStack>(Collections.nCopies(40, LegacyItems.EMPTY));
        var snapshot = new InventorySnapshot(items, new int[40]);
        ItemStack iron = new ItemStack(Items.iron_chestplate),
                diamond = new ItemStack(Items.diamond_chestplate);
        check(
                InventoryArmor.damage(snapshot, 38, diamond)
                        < InventoryArmor.damage(snapshot, 38, iron),
                "ordinary armor quality uses old flat reduction");
        iron.addEnchantment(Enchantment.protection, 4);
        double protectedDamage = InventoryArmor.damage(snapshot, 38, iron);
        check(
                protectedDamage < InventoryArmor.damage(snapshot, 38, diamond),
                "protection IV can outrank unenchanted diamond");
        for (int i = 0; i < 30; i++)
            check(
                    InventoryArmor.damage(snapshot, 38, iron) == protectedDamage,
                    "EPF planner remains deterministic");
        System.out.println(
                "LEGACY_WORLD_INVENTORY_VERIFIED geometry=5 predicted-slab=true fluid-policy=true null-stack=true metadata=true sharpness=true armor-epf=true");
    }

    private static AxisAlignedBB box(double a, double b, double c, double d, double e, double f) {
        return new AxisAlignedBB(a, b, c, d, e, f);
    }

    private static LegacyWorld.Shape shape(AxisAlignedBB... boxes) {
        return new LegacyWorld.Shape(List.of(boxes));
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static final class EmptyView implements IBlockAccess {
        public IBlockState getBlockState(BlockPos pos) {
            return Blocks.air.getDefaultState();
        }

        public TileEntity getTileEntity(BlockPos pos) {
            return null;
        }

        public int getCombinedLight(BlockPos pos, int light) {
            return light;
        }

        public boolean isAirBlock(BlockPos pos) {
            return true;
        }

        public BiomeGenBase getBiomeGenForCoords(BlockPos pos) {
            return BiomeGenBase.plains;
        }

        public boolean extendedLevelsInChunkCache() {
            return false;
        }

        public int getStrongPower(BlockPos pos, EnumFacing facing) {
            return 0;
        }

        public WorldType getWorldType() {
            return WorldType.DEFAULT;
        }
    }
}
