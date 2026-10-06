package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredBox;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.List;

/** Small shared overlay geometry; all placement decisions remain in ScaffoldManager. */
final class ScaffoldTargetRenderer {
    private static final int TARGET_COLOR = 0x75CFFF;
    private static final int PLACED_COLOR = 0x8EE4CC;
    private static final double EDGE_WIDTH = 0.006D;

    private ScaffoldTargetRenderer() {}

    static void target(List<ColoredBox> boxes, BlockPos pos, boolean shade) {
        if (shade) {
            box(
                    boxes,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    pos.getX() + 1,
                    pos.getY() + 1,
                    pos.getZ() + 1,
                    TARGET_COLOR,
                    0.07F);
        }
        outline(boxes, pos, TARGET_COLOR, 0.58F);
    }

    static void placed(List<ColoredBox> boxes, BlockPos pos, float alpha) {
        outline(boxes, pos, PLACED_COLOR, alpha);
    }

    private static void outline(List<ColoredBox> boxes, BlockPos pos, int color, float alpha) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        double width = EDGE_WIDTH;
        for (int first = 0; first <= 1; first++) {
            for (int second = 0; second <= 1; second++) {
                box(
                        boxes,
                        x - width,
                        y + first - width,
                        z + second - width,
                        x + 1 + width,
                        y + first + width,
                        z + second + width,
                        color,
                        alpha);
                box(
                        boxes,
                        x + first - width,
                        y - width,
                        z + second - width,
                        x + first + width,
                        y + 1 + width,
                        z + second + width,
                        color,
                        alpha);
                box(
                        boxes,
                        x + first - width,
                        y + second - width,
                        z - width,
                        x + first + width,
                        y + second + width,
                        z + 1 + width,
                        color,
                        alpha);
            }
        }
    }

    /** The marker lies on the traced support face, including side and underside placements. */
    static void hit(List<ColoredBox> boxes, MovingObjectPosition hit) {
        EnumFacing face = hit.sideHit;
        Vec3 point =
                hit.hitVec.addVector(
                        face.getFrontOffsetX() * 0.004D,
                        face.getFrontOffsetY() * 0.004D,
                        face.getFrontOffsetZ() * 0.004D);
        double radius = 0.055D;
        double width = 0.006D;
        facePatch(
                boxes,
                point,
                face.getAxis(),
                -radius,
                -radius,
                radius,
                -radius + width,
                TARGET_COLOR,
                0.78F);
        facePatch(
                boxes,
                point,
                face.getAxis(),
                -radius,
                radius - width,
                radius,
                radius,
                TARGET_COLOR,
                0.78F);
        facePatch(
                boxes,
                point,
                face.getAxis(),
                -radius,
                -radius + width,
                -radius + width,
                radius - width,
                TARGET_COLOR,
                0.78F);
        facePatch(
                boxes,
                point,
                face.getAxis(),
                radius - width,
                -radius + width,
                radius,
                radius - width,
                TARGET_COLOR,
                0.78F);
        facePatch(boxes, point, face.getAxis(), -0.009D, -0.009D, 0.009D, 0.009D, 0xF0FAFF, 0.95F);
    }

    private static void facePatch(
            List<ColoredBox> boxes,
            Vec3 point,
            EnumFacing.Axis axis,
            double minU,
            double minV,
            double maxU,
            double maxV,
            int color,
            float alpha) {
        double depth = 0.002D;
        switch (axis) {
            case X ->
                    box(
                            boxes,
                            point.xCoord - depth,
                            point.yCoord + minU,
                            point.zCoord + minV,
                            point.xCoord + depth,
                            point.yCoord + maxU,
                            point.zCoord + maxV,
                            color,
                            alpha);
            case Y ->
                    box(
                            boxes,
                            point.xCoord + minU,
                            point.yCoord - depth,
                            point.zCoord + minV,
                            point.xCoord + maxU,
                            point.yCoord + depth,
                            point.zCoord + maxV,
                            color,
                            alpha);
            case Z ->
                    box(
                            boxes,
                            point.xCoord + minU,
                            point.yCoord + minV,
                            point.zCoord - depth,
                            point.xCoord + maxU,
                            point.yCoord + maxV,
                            point.zCoord + depth,
                            color,
                            alpha);
        }
    }

    private static void box(
            List<ColoredBox> boxes,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            int color,
            float alpha) {
        boxes.add(
                new ColoredBox(
                        (float) minX,
                        (float) minY,
                        (float) minZ,
                        (float) maxX,
                        (float) maxY,
                        (float) maxZ,
                        (color >> 16 & 0xFF) / 255.0F,
                        (color >> 8 & 0xFF) / 255.0F,
                        (color & 0xFF) / 255.0F,
                        alpha));
    }
}
