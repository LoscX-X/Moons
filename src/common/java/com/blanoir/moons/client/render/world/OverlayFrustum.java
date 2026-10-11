package com.blanoir.moons.client.render.world;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Reused screen-frustum filter; never tests terrain occlusion. */
public final class OverlayFrustum {
    private final Matrix4f matrix = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private double eyeX, eyeY, eyeZ;

    public void update(Camera camera) {
        var client = net.minecraft.client.Minecraft.getInstance();
        matrix.set(client.gameRenderer.getProjectionMatrix(client.options.fov().get().floatValue()))
                .rotate(camera.rotation().conjugate(new org.joml.Quaternionf()));
        set(matrix, camera.position());
    }

    public void set(Matrix4fc projection, Vec3 eye) {
        frustum.set(projection);
        eyeX = eye.x;
        eyeY = eye.y;
        eyeZ = eye.z;
    }

    public boolean isVisible(double x1, double y1, double z1, double x2, double y2, double z2) {
        // A small margin keeps outlines at the screen edge and camera bob from popping.
        return frustum.testAab(
                (float) (x1 - eyeX - .25),
                (float) (y1 - eyeY - .25),
                (float) (z1 - eyeZ - .25),
                (float) (x2 - eyeX + .25),
                (float) (y2 - eyeY + .25),
                (float) (z2 - eyeZ + .25));
    }
}
