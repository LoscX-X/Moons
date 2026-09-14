package com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.math;

import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;

public class Sqrt implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        double value = arguments.getAsDouble(context, 0);
        // 原版 molang 语义：负数开方返回 0（NaN 会污染骨骼矩阵 → 骨骼乱飘/消失）
        return value < 0.0 ? 0 : Math.sqrt(value);
    }

    @Override
    public float evaluateFloat(ExpressionEvaluator<?> context, ArgumentCollection arguments) {
        double value = arguments.getAsDouble(context, 0);
        return value < 0.0 ? 0.0f : (float) Math.sqrt(value);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
