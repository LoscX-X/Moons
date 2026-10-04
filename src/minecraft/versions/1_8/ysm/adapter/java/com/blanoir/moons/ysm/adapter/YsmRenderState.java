package com.blanoir.moons.ysm.adapter;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

/** Immutable observation without changing the live render pose. */
final class YsmRenderState {
    final float ageInTicks, bodyRot, xRot, yRot, walkAnimationSpeed, walkAnimationPos, attackTime;
    final double x, y, z;
    final int id, lightCoords;
    final boolean hasRedOverlay;

    YsmRenderState(Entity e, float partial) {
        ageInTicks = e.ticksExisted + partial;
        x = e.lastTickPosX + (e.posX - e.lastTickPosX) * partial;
        y = e.lastTickPosY + (e.posY - e.lastTickPosY) * partial;
        z = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partial;
        id = e.getEntityId();
        lightCoords = e.getBrightnessForRender(partial);
        xRot = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * partial;
        if (e instanceof EntityLivingBase l) {
            bodyRot = angle(partial, l.prevRenderYawOffset, l.renderYawOffset);
            yRot = angle(partial, l.prevRotationYawHead, l.rotationYawHead) - bodyRot;
            walkAnimationSpeed =
                    l.prevLimbSwingAmount + (l.limbSwingAmount - l.prevLimbSwingAmount) * partial;
            walkAnimationPos = l.limbSwing - l.limbSwingAmount * (1 - partial);
            attackTime = l.getSwingProgress(partial);
            hasRedOverlay = l.hurtTime > 0 || l.deathTime > 0;
        } else {
            bodyRot = angle(partial, e.prevRotationYaw, e.rotationYaw);
            yRot = 0;
            walkAnimationSpeed = 0;
            walkAnimationPos = 0;
            attackTime = 0;
            hasRedOverlay = false;
        }
    }

    static float angle(float p, float previous, float current) {
        return previous + MathHelper.wrapAngleTo180_float(current - previous) * p;
    }
}
