package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.entity.EntityPlayerSP;

import org.joml.Matrix4f;

final class YsmPlayerTransform {
    static Matrix4f apply(EntityPlayerSP p, YsmRenderState s, double scale) {
        Matrix4f pose = new Matrix4f();
        if (p.isPlayerSleeping())
            pose.rotateY((float) Math.toRadians(p.getBedOrientationInDegrees()))
                    .rotateZ((float) Math.PI / 2)
                    .rotateY((float) Math.PI * 1.5f);
        else pose.rotateY((float) Math.toRadians(180 - s.bodyRot));
        if (p.isSneaking()) pose.translate(0, -.125f, 0);
        return pose.scale((float) scale * .9375f);
    }
}
