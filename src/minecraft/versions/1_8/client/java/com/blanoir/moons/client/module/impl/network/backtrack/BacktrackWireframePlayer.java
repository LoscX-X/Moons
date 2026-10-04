package com.blanoir.moons.client.module.impl.network.backtrack;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.render.LegacyPoseStack;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import org.joml.Quaternionf;

/**
 * Wireframe player model used by the Backtrack ESP.
 */
public final class BacktrackWireframePlayer {
    private static final AxisAlignedBB LIMB = new AxisAlignedBB(0.0, 0.0, 0.0, 0.125, 0.375, 0.125);
    private static final AxisAlignedBB BODY = new AxisAlignedBB(0.0, 0.0, 0.0, 0.25, 0.375, 0.125);
    private static final AxisAlignedBB HEAD = new AxisAlignedBB(0.0, 0.0, 0.0, 0.25, 0.25, 0.25);

    private static final AxisAlignedBB RENDER_LEFT_LEG = LIMB.offset(-LIMB.maxX, 0.0, 0.0);
    private static final AxisAlignedBB RENDER_RIGHT_LEG = LIMB;
    private static final AxisAlignedBB RENDER_BODY = BODY.offset(-LIMB.maxX, LIMB.maxY, 0.0);
    private static final AxisAlignedBB RENDER_LEFT_ARM =
            LIMB.offset(-2 * LIMB.maxX, LIMB.maxY, 0.0);
    private static final AxisAlignedBB RENDER_RIGHT_ARM =
            LIMB.offset(BODY.maxX - LIMB.maxX, LIMB.maxY, 0.0);
    private static final AxisAlignedBB RENDER_HEAD =
            HEAD.offset(-LIMB.maxX, LIMB.maxY * 2, -HEAD.maxZ * 0.25);

    private static final float MODEL_SCALE = 1.9f;

    private static final float CROUCH_BODY_ROTATION = 28.64789f;
    private static final float CROUCH_ARM_ROTATION = 22.918312f;

    private static final AxisAlignedBB CROUCH_LEFT_LEG = RENDER_LEFT_LEG.offset(0.0, 0.0, 0.125);
    private static final AxisAlignedBB CROUCH_RIGHT_LEG = RENDER_RIGHT_LEG.offset(0.0, 0.0, 0.125);
    private static final AxisAlignedBB CROUCH_BODY = RENDER_BODY.offset(0.0, -0.12, 0.05);
    private static final AxisAlignedBB CROUCH_LEFT_ARM = RENDER_LEFT_ARM.offset(0.0, -0.12, 0.03);
    private static final AxisAlignedBB CROUCH_RIGHT_ARM = RENDER_RIGHT_ARM.offset(0.0, -0.12, 0.03);
    private static final AxisAlignedBB CROUCH_HEAD = RENDER_HEAD.offset(0.0, -0.18, 0.1);

    private float yRot;
    private float xRot;
    private Pose pose = Pose.STANDING;

    public enum Pose {
        STANDING,
        CROUCHING
    }

    public void setRotation(float xRot, float yRot) {
        this.xRot = xRot;
        this.yRot = yRot;
    }

    public void setPose(Pose pose) {
        this.pose = pose;
    }

    public void render(LegacyPoseStack matrices, int color, int outlineColor) {
        float bodyYaw = -Mth.wrapDegrees(this.yRot);
        BacktrackRenderer.BoxBatch batch = new BacktrackRenderer.BoxBatch();

        matrices.pushPose();
        try {
            matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(bodyYaw)));
            matrices.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);

            switch (this.pose) {
                case CROUCHING -> renderCrouching(matrices, batch);

                default -> renderStanding(matrices, batch);
            }
        } finally {
            matrices.popPose();
        }
        batch.render(color, outlineColor, "backtrack wireframe");
    }

    private void renderStanding(LegacyPoseStack matrices, BacktrackRenderer.BoxBatch batch) {
        renderPart(matrices, batch, RENDER_LEFT_LEG, center(RENDER_LEFT_LEG), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_RIGHT_LEG, center(RENDER_RIGHT_LEG), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_BODY, center(RENDER_BODY), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_LEFT_ARM, center(RENDER_LEFT_ARM), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_RIGHT_ARM, center(RENDER_RIGHT_ARM), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_HEAD, bottomCenter(RENDER_HEAD), this.xRot, 0f, 0f);
    }

    private void renderCrouching(LegacyPoseStack matrices, BacktrackRenderer.BoxBatch batch) {
        renderPart(matrices, batch, CROUCH_LEFT_LEG, center(CROUCH_LEFT_LEG), 0f, 0f, 0f);
        renderPart(matrices, batch, CROUCH_RIGHT_LEG, center(CROUCH_RIGHT_LEG), 0f, 0f, 0f);
        renderPart(
                matrices,
                batch,
                CROUCH_BODY,
                bottomCenter(CROUCH_BODY),
                CROUCH_BODY_ROTATION,
                0f,
                0f);
        renderPart(
                matrices,
                batch,
                CROUCH_LEFT_ARM,
                bottomCenter(CROUCH_LEFT_ARM),
                CROUCH_ARM_ROTATION,
                0f,
                0f);
        renderPart(
                matrices,
                batch,
                CROUCH_RIGHT_ARM,
                bottomCenter(CROUCH_RIGHT_ARM),
                CROUCH_ARM_ROTATION,
                0f,
                0f);
        renderPart(matrices, batch, CROUCH_HEAD, bottomCenter(CROUCH_HEAD), this.xRot, 0f, 0f);
    }

    private void renderPart(
            LegacyPoseStack matrices,
            BacktrackRenderer.BoxBatch batch,
            AxisAlignedBB box,
            Vec3 pivot,
            float xRot,
            float yRot,
            float zRot) {
        matrices.pushPose();

        if (xRot != 0f || yRot != 0f || zRot != 0f) {
            matrices.translate(pivot.xCoord, pivot.yCoord, pivot.zCoord);

            if (zRot != 0f) {
                matrices.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(zRot)));
            }
            if (yRot != 0f) {
                matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(yRot)));
            }
            if (xRot != 0f) {
                matrices.mulPose(new Quaternionf().rotationX((float) Math.toRadians(xRot)));
            }

            matrices.translate(-pivot.xCoord, -pivot.yCoord, -pivot.zCoord);
        }

        batch.add(matrices, box);

        matrices.popPose();
    }

    private static Vec3 center(AxisAlignedBB box) {
        return new Vec3(
                box.minX + (box.maxX - box.minX) / 2.0,
                box.minY + (box.maxY - box.minY) / 2.0,
                box.minZ + (box.maxZ - box.minZ) / 2.0);
    }

    private static Vec3 bottomCenter(AxisAlignedBB box) {
        return new Vec3(
                box.minX + (box.maxX - box.minX) / 2.0,
                box.minY,
                box.minZ + (box.maxZ - box.minZ) / 2.0);
    }
}
