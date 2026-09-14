package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;
import com.blanoir.moons.ysm.internal.runtime.ScalarMath;

public class Floor implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return ScalarMath.floor(arguments.getAsFloat(context, 0));
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        return ScalarMath.floor(arguments.getAsFloatRaw(context, 0));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
