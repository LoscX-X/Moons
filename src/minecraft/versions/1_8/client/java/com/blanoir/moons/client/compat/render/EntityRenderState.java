package com.blanoir.moons.client.compat.render;

/** Per-call render coordinates; changing these never changes the entity's world position. */
public final class EntityRenderState {
    public double x, y, z, distanceToCameraSq;

    public EntityRenderState(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.distanceToCameraSq = x * x + y * y + z * z;
    }
}
