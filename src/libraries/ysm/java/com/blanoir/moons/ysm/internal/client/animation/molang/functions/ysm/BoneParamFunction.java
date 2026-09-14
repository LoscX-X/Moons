package com.blanoir.moons.ysm.internal.client.animation.molang.functions.ysm;

import com.blanoir.moons.ysm.internal.client.animation.molang.struct.Vec3fStruct;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.funciton.ContextFunction;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;
import com.blanoir.moons.ysm.internal.geckolib3.core.processor.IBone;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;

import org.jetbrains.annotations.NotNull;

public abstract class BoneParamFunction extends ContextFunction<Object> {
    public abstract Vec3fStruct getParam(@NotNull IBone bone);

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }

    @Override
    public Object eval(ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        IBone bone;
        int name = arguments.getStringId(context, 0);
        if (name == StringPool.EMPTY_ID
                || (bone = context.entity().geoInstance().getBone(name)) == null) {
            return null;
        }
        return getParam(bone);
    }
}
