package com.blanoir.moons.ysm.internal.client.animation.molang.functions.ysm;

import com.blanoir.moons.ysm.internal.geckolib3.core.controller.AnimationControllerContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.funciton.ContextFunction;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;

public class Defer extends ContextFunction<Object> {
    @Override
    public Object eval(ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        AnimationControllerContext animationControllerContext;
        int i;
        if (context.entity().isClientSide()
                && (animationControllerContext = context.entity().animationControllerContext())
                        != null
                && (i = arguments.getStringId(context, 0)) != StringPool.EMPTY_ID) {
            animationControllerContext.captureArguments(context, i, arguments, 1);
            return null;
        }
        return null;
    }
}
