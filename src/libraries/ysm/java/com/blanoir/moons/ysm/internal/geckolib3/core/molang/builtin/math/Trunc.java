package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;
import com.blanoir.moons.ysm.internal.runtime.ScalarMath;

public class Trunc implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        float value = arguments.getAsFloat(context, 0);
        return value < 0 ? ScalarMath.ceil(value) : ScalarMath.floor(value);
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        float value = arguments.getAsFloatRaw(context, 0);
        return value < 0 ? ScalarMath.ceil(value) : ScalarMath.floor(value);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
