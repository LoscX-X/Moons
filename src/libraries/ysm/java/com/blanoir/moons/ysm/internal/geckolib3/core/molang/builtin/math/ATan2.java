package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;

public class ATan2 implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return Math.atan2(arguments.getAsDouble(context, 0), arguments.getAsDouble(context, 1));
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        return (float)
                Math.atan2(arguments.getAsDouble(context, 0), arguments.getAsDouble(context, 1));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 2;
    }
}
