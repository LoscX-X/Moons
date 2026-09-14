package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;

public class Pow implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        double result =
                Math.pow(arguments.getAsDouble(context, 0), arguments.getAsDouble(context, 1));
        // 原版 molang 语义：无效幂（负底数×非整数指数等）返回 0，避免 NaN 扩散到骨骼矩阵
        return Double.isFinite(result) ? result : 0;
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        double result =
                Math.pow(arguments.getAsDouble(context, 0), arguments.getAsDouble(context, 1));
        return Double.isFinite(result) ? (float) result : 0.0f;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 2;
    }
}
