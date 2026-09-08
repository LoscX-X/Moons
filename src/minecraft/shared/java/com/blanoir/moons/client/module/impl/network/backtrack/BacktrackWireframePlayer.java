package com.blanoir.moons.client.module.impl.network.backtrack;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * Wireframe player model used by the Backtrack ESP.
 *
 * <p>Source attribution is recorded in THIRD_PARTY_NOTICES.md.
 */
public final class BacktrackWireframePlayer {
    private static final AABB LIMB = new AABB(0.0, 0.0, 0.0, 0.125, 0.375, 0.125);
    private static final AABB BODY = new AABB(0.0, 0.0, 0.0, 0.25, 0.375, 0.125);
    private static final AABB HEAD = new AABB(0.0, 0.0, 0.0, 0.25, 0.25, 0.25);

    private static final AABB RENDER_LEFT_LEG = LIMB.move(-LIMB.maxX, 0.0, 0.0);
    private static final AABB RENDER_RIGHT_LEG = LIMB;
    private static final AABB RENDER_BODY = BODY.move(-LIMB.maxX, LIMB.maxY, 0.0);
    private static final AABB RENDER_LEFT_ARM = LIMB.move(-2 * LIMB.maxX, LIMB.maxY, 0.0);
    private static final AABB RENDER_RIGHT_ARM = LIMB.move(BODY.maxX - LIMB.maxX, LIMB.maxY, 0.0);
    private static final AABB RENDER_HEAD = HEAD.move(-LIMB.maxX, LIMB.maxY * 2, -HEAD.maxZ * 0.25);

    private static final float MODEL_SCALE = 1.9f;

    private static final float CROUCH_BODY_ROTATION = 28.64789f;
    private static final float CROUCH_ARM_ROTATION = 22.918312f;

    private static final AABB CROUCH_LEFT_LEG = RENDER_LEFT_LEG.move(0.0, 0.0, 0.125);
    private static final AABB CROUCH_RIGHT_LEG = RENDER_RIGHT_LEG.move(0.0, 0.0, 0.125);
    private static final AABB CROUCH_BODY = RENDER_BODY.move(0.0, -0.12, 0.05);
    private static final AABB CROUCH_LEFT_ARM = RENDER_LEFT_ARM.move(0.0, -0.12, 0.03);
    private static final AABB CROUCH_RIGHT_ARM = RENDER_RIGHT_ARM.move(0.0, -0.12, 0.03);
    private static final AABB CROUCH_HEAD = RENDER_HEAD.move(0.0, -0.18, 0.1);

    private static final float SWIM_PART_ROTATION = 90f;
    private static final float SWIM_HEAD_TARGET_ROTATION = -45f;
    private static final float SWIM_LEFT_ARM_ROLL = -15f;
    private static final float SWIM_RIGHT_ARM_ROLL = 15f;
    private static final float SWIM_LEFT_LEG_ROLL = -6f;
    private static final float SWIM_RIGHT_LEG_ROLL = 6f;
    private static final double SWIM_ROOT_Y_OFFSET = -0.4375;

    private float yRot;
    private float xRot;
    private Pose pose = Pose.STANDING;
    private float swimAmount;

    public void setRotation(float xRot, float yRot) {
        this.xRot = xRot;
        this.yRot = yRot;
    }

    public void setPose(Pose pose) {
        this.pose = pose;
    }

    public void setSwimAmount(float swimAmount) {
        this.swimAmount = swimAmount;
    }

    public void render(PoseStack matrices, int color, int outlineColor) {
        float bodyYaw = -Mth.wrapDegrees(this.yRot);
        BacktrackRenderer.BoxBatch batch = new BacktrackRenderer.BoxBatch();

        matrices.pushPose();
        try {
            matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(bodyYaw)));
            matrices.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);

            switch (this.pose) {
                case CROUCHING -> renderCrouching(matrices, batch);
                case SWIMMING -> renderSwimming(matrices, batch);
                default -> renderStanding(matrices, batch);
            }
        } finally {
            matrices.popPose();
        }
        batch.render(color, outlineColor, "backtrack wireframe");
    }

    private void renderStanding(PoseStack matrices, BacktrackRenderer.BoxBatch batch) {
        renderPart(matrices, batch, RENDER_LEFT_LEG, center(RENDER_LEFT_LEG), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_RIGHT_LEG, center(RENDER_RIGHT_LEG), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_BODY, center(RENDER_BODY), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_LEFT_ARM, center(RENDER_LEFT_ARM), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_RIGHT_ARM, center(RENDER_RIGHT_ARM), 0f, 0f, 0f);
        renderPart(matrices, batch, RENDER_HEAD, bottomCenter(RENDER_HEAD), this.xRot, 0f, 0f);
    }

    private void renderCrouching(PoseStack matrices, BacktrackRenderer.BoxBatch batch) {
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

    private void renderSwimming(PoseStack matrices, BacktrackRenderer.BoxBatch batch) {
        float swimProgress = this.swimAmount > 0f ? this.swimAmount : 1f;
        float swimHeadRotation = Mth.lerp(swimProgress, this.xRot, SWIM_HEAD_TARGET_ROTATION);

        Vec3 bodyCenter = center(RENDER_BODY);

        matrices.pushPose();
        matrices.translate(bodyCenter.x, bodyCenter.y + SWIM_ROOT_Y_OFFSET, bodyCenter.z);
        matrices.mulPose(new Quaternionf().rotationX((float) Math.toRadians(SWIM_PART_ROTATION)));
        matrices.translate(-bodyCenter.x, -bodyCenter.y, -bodyCenter.z);

        renderPart(matrices, batch, RENDER_BODY, center(RENDER_BODY), 0f, 0f, 0f);
        renderPart(
                matrices,
                batch,
                RENDER_LEFT_ARM,
                center(RENDER_LEFT_ARM),
                0f,
                0f,
                SWIM_LEFT_ARM_ROLL);
        renderPart(
                matrices,
                batch,
                RENDER_RIGHT_ARM,
                center(RENDER_RIGHT_ARM),
                0f,
                0f,
                SWIM_RIGHT_ARM_ROLL);
        renderPart(
                matrices,
                batch,
                RENDER_LEFT_LEG,
                center(RENDER_LEFT_LEG),
                0f,
                0f,
                SWIM_LEFT_LEG_ROLL);
        renderPart(
                matrices,
                batch,
                RENDER_RIGHT_LEG,
                center(RENDER_RIGHT_LEG),
                0f,
                0f,
                SWIM_RIGHT_LEG_ROLL);
        renderPart(
                matrices, batch, RENDER_HEAD, bottomCenter(RENDER_HEAD), swimHeadRotation, 0f, 0f);

        matrices.popPose();
    }

    private void renderPart(
            PoseStack matrices,
            BacktrackRenderer.BoxBatch batch,
            AABB box,
            Vec3 pivot,
            float xRot,
            float yRot,
            float zRot) {
        matrices.pushPose();

        if (xRot != 0f || yRot != 0f || zRot != 0f) {
            matrices.translate(pivot.x, pivot.y, pivot.z);

            if (zRot != 0f) {
                matrices.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(zRot)));
            }
            if (yRot != 0f) {
                matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(yRot)));
            }
            if (xRot != 0f) {
                matrices.mulPose(new Quaternionf().rotationX((float) Math.toRadians(xRot)));
            }

            matrices.translate(-pivot.x, -pivot.y, -pivot.z);
        }

        batch.add(matrices, box);

        matrices.popPose();
    }

    private static Vec3 center(AABB box) {
        return new Vec3(
                box.minX + (box.maxX - box.minX) / 2.0,
                box.minY + (box.maxY - box.minY) / 2.0,
                box.minZ + (box.maxZ - box.minZ) / 2.0);
    }

    private static Vec3 bottomCenter(AABB box) {
        return new Vec3(
                box.minX + (box.maxX - box.minX) / 2.0,
                box.minY,
                box.minZ + (box.maxZ - box.minZ) / 2.0);
    }
}
