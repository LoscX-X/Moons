package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;

public class Mod implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return arguments.getAsFloat(context, 0) % arguments.getAsFloat(context, 1);
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        return arguments.getAsFloatRaw(context, 0) % arguments.getAsFloatRaw(context, 1);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 2;
    }
}
