package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.render.LegacyPoseStack;
import com.blanoir.moons.client.render.WorldOverlayRenderer;

import net.minecraft.util.AxisAlignedBB;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public final class BacktrackRenderer {
    private static final int[][] FACES = {
        {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}
    };
    private static final int[][] EDGES = {
        {0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {3, 7},
        {2, 6}
    };

    private BacktrackRenderer() {}

    public static void renderBox(
            LegacyPoseStack poses, AxisAlignedBB box, int fill, int outline, String label) {
        var batch = new BoxBatch();
        batch.add(poses, box);
        batch.render(fill, outline, label);
    }

    public static final class BoxBatch {
        private final List<Vector3f[]> boxes = new ArrayList<>();

        public void add(LegacyPoseStack poses, AxisAlignedBB box) {
            var corners = new Vector3f[8];
            for (int i = 0; i < 8; i++)
                corners[i] =
                        poses.last()
                                .pose()
                                .transformPosition(
                                        (float) ((i & 1) == 0 ? box.minX : box.maxX),
                                        (float) ((i & 2) == 0 ? box.minY : box.maxY),
                                        (float) ((i & 4) == 0 ? box.minZ : box.maxZ),
                                        new Vector3f());
            boxes.add(corners);
        }

        public void render(int fill, int outline, String label) {
            if (boxes.isEmpty()) return;
            if ((fill >>> 24) != 0) draw(GL11.GL_QUADS, FACES, fill);
            if ((outline >>> 24) != 0) draw(GL11.GL_LINES, EDGES, outline);
        }

        private void draw(int primitive, int[][] indices, int color) {
            WorldOverlayRenderer.batch(
                    primitive,
                    vertices -> {
                        for (var box : boxes)
                            for (var face : indices)
                                for (int index : face) {
                                    var p = box[index];
                                    vertices.addVertex(p.x, p.y, p.z).setColor(color);
                                }
                    });
        }
    }

    public static void close() {
        WorldOverlayRenderer.close();
    }
}
