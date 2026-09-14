package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;
import com.blanoir.moons.ysm.internal.runtime.ScalarMath;

public class HermitBlend implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        double min = ScalarMath.ceil(arguments.getAsFloat(context, 0));
        return ScalarMath.floor((3.0d * Math.pow(min, 2.0d)) - (2.0d * Math.pow(min, 3.0d)));
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        double min = ScalarMath.ceil(arguments.getAsFloatRaw(context, 0));
        return ScalarMath.floor((3.0d * Math.pow(min, 2.0d)) - (2.0d * Math.pow(min, 3.0d)));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
