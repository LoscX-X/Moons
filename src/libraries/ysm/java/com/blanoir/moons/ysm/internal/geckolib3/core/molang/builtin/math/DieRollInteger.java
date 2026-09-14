package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.funciton.ContextFunction;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;

public class DieRollInteger extends ContextFunction<Object> {
    @Override
    public boolean validateArgumentSize(int size) {
        return size == 3;
    }

    @Override
    protected Object eval(
            ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        int i = Math.round(arguments.getAsFloat(context, 0));
        int min = arguments.getAsInt(context, 1);
        int range = arguments.getAsInt(context, 2);
        if (min > range) {
            int temp = min;
            min = range;
            range = temp - range;
        } else {
            range -= min;
        }
        int total = 0;
        java.util.Random rnd = context.entity().random();
        while (i-- > 0) {
            total += min + rnd.nextInt(range);
        }
        return total;
    }

    @Override
    protected float evalFloat(
            ExpressionEvaluator<IContext<Object>> context, ArgumentCollection arguments) {
        int i = Math.round(arguments.getAsFloatRaw(context, 0));
        int min = arguments.getAsInt(context, 1);
        int range = arguments.getAsInt(context, 2);
        if (min > range) {
            int temp = min;
            min = range;
            range = temp - range;
        } else {
            range -= min;
        }
        int total = 0;
        java.util.Random rnd = context.entity().random();
        while (i-- > 0) {
            total += min + rnd.nextInt(range);
        }
        return total;
    }
}
