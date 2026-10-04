package com.blanoir.moons.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Camera view used by Moons overlays, sampled from the current 1.8 world render. */
public final class LegacyCamera {
    private final Vec3 position;

    public LegacyCamera(float partialTick) {
        position = LegacyRenderContext.capture(partialTick).cameraPosition();
    }

    public Vec3 position() {
        return position;
    }

    public Quaternionf rotation() {
        var client = Minecraft.getMinecraft();
        var r = client.getRenderManager();
        float
                yaw =
                        com.blanoir.moons.client.module.impl.misc.FreeLook.cameraAngle(
                                client.entityRenderer, 0, r.playerViewY),
                pitch =
                        com.blanoir.moons.client.module.impl.misc.FreeLook.cameraAngle(
                                client.entityRenderer, 1, r.playerViewX);
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
    }

    public Matrix4f getViewRotationProjectionMatrix(Matrix4f destination) {
        var projection = BufferUtils.createFloatBuffer(16);
        var model = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
        return destination.set(projection).mul(new Matrix4f(model));
    }
}
