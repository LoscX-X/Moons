package com.blanoir.tempos.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.tempos.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.tempos.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.tempos.ysm.internal.molang.runtime.Function;
import com.blanoir.tempos.ysm.internal.runtime.ScalarMath;

public class Cos implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return ScalarMath.cos(arguments.getAsFloat(context, 0) / 180.0f * 3.1415927f);
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        return ScalarMath.cos(arguments.getAsFloatRaw(context, 0) / 180.0f * 3.1415927f);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
