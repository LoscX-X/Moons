package com.blanoir.moons.client.render;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** Vertex emitter used inside a GL primitive batch. Color completes each vertex. */
public final class LegacyVertexConsumer {
    private float x, y, z, u, v;
    private boolean textured;

    public LegacyVertexConsumer addVertex(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        textured = false;
        return this;
    }

    public LegacyVertexConsumer addVertex(Matrix4fc pose, float x, float y, float z) {
        Vector3f p = pose.transformPosition(x, y, z, new Vector3f());
        return addVertex(p.x, p.y, p.z);
    }

    public LegacyVertexConsumer setUv(float u, float v) {
        this.u = u;
        this.v = v;
        textured = true;
        return this;
    }

    public LegacyVertexConsumer setColor(int argb) {
        return setColor(
                (argb >> 16 & 255) / 255f,
                (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f,
                (argb >>> 24) / 255f);
    }

    public LegacyVertexConsumer setColor(float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        if (textured) GL11.glTexCoord2f(u, v);
        GL11.glVertex3f(x, y, z);
        return this;
    }
}
