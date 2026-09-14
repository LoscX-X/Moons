package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.funciton.ContextFunction;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;

public class DieRoll extends ContextFunction<Object> {
    @Override
    public boolean validateArgumentSize(int size) {
        return size == 3;
    }

    @Override
    protected Object eval(
            ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        int i = arguments.getAsInt(context, 0);
        float min = arguments.getAsFloat(context, 1);
        float range = arguments.getAsFloat(context, 2);
        if (min > range) {
            float temp = min;
            min = range;
            range = temp - range;
        } else {
            range -= min;
        }
        float total = 0;
        java.util.Random rnd = context.entity().random();
        while (i-- > 0) {
            total += min + rnd.nextFloat() * range;
        }
        return total;
    }

    @Override
    protected float evalFloat(
            ExpressionEvaluator<IContext<Object>> context, ArgumentCollection arguments) {
        int i = arguments.getAsInt(context, 0);
        float min = arguments.getAsFloatRaw(context, 1);
        float range = arguments.getAsFloatRaw(context, 2);
        if (min > range) {
            float temp = min;
            min = range;
            range = temp - range;
        } else {
            range -= min;
        }
        float total = 0;
        java.util.Random rnd = context.entity().random();
        while (i-- > 0) {
            total += min + rnd.nextFloat() * range;
        }
        return total;
    }
}
