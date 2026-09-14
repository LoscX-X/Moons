package com.blanoir.moons.ysm.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/** Upstream player orientation, without temporary mutation of the real entity's flags. */
final class YsmPlayerTransform {
    static void apply(
            LocalPlayer player,
            AvatarRenderState state,
            PoseStack pose,
            double scale,
            boolean animationHandlesFlyingPitch) {
        float yaw = state.bodyRot;
        if (player.onClimbable()) {
            var position = player.getLastClimbablePos();
            if (position.isPresent()) {
                var facing =
                        player.level()
                                .getBlockState(position.get())
                                .getOptionalValue(HorizontalDirectionalBlock.FACING);
                if (facing.isPresent()) yaw = facing.get().getOpposite().get2DDataValue() * 90;
            }
        }
        if (player.isSleeping()) {
            Direction direction = player.getBedOrientation();
            if (direction != null) {
                float eye = player.getEyeHeight(Pose.STANDING) - .1f;
                pose.translate(-direction.getStepX() * eye, 0, -direction.getStepZ() * eye);
            }
            float angle =
                    direction == null
                            ? yaw
                            : switch (direction) {
                                case SOUTH -> 90;
                                case WEST -> 0;
                                case NORTH -> 270;
                                case EAST -> 180;
                                default -> 0;
                            };
            pose.rotate(Axis.YP.rotationDegrees(angle));
            pose.rotate(Axis.ZP.rotationDegrees(90));
            pose.rotate(Axis.YP.rotationDegrees(270));
        } else {
            pose.rotate(Axis.YP.rotationDegrees(180 - yaw));
            if (player.isFallFlying()) {
                if (!player.isAutoSpinAttack() && !animationHandlesFlyingPitch)
                    pose.rotate(
                            Axis.XP.rotationDegrees(
                                    state.fallFlyingScale() * (-90 - player.getXRot())));
                if (state.shouldApplyFlyingYRot) pose.rotate(Axis.YP.rotation(state.flyingYRot));
            }
        }
        float size = (float) scale * state.scale * .9375f;
        pose.scale(size, size, size);
    }
}
