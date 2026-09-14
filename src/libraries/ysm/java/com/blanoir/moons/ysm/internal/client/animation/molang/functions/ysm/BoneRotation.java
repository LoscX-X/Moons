package com.blanoir.moons.ysm.internal.client.animation.molang.functions.ysm;

import com.blanoir.moons.ysm.internal.client.animation.molang.struct.Vec3fStruct;
import com.blanoir.moons.ysm.internal.geckolib3.core.processor.IBone;

import org.jetbrains.annotations.NotNull;

public final class BoneRotation extends BoneParamFunction {
    @Override
    public Vec3fStruct getParam(@NotNull IBone bone) {
        return new BoneRotationStruct(bone);
    }

    private static final class BoneRotationStruct extends Vec3fStruct {

        private final IBone boneTransform;

        public BoneRotationStruct(IBone bone) {
            this.boneTransform = bone;
        }

        @Override
        public float getX() {
            return -((float) Math.toDegrees(this.boneTransform.getRotationX()));
        }

        @Override
        public float getY() {
            return -((float) Math.toDegrees(this.boneTransform.getRotationY()));
        }

        @Override
        public float getZ() {
            return (float) Math.toDegrees(this.boneTransform.getRotationZ());
        }
    }
}
