package com.blanoir.moons.ysm.internal.geckolib3.core.molang.value;

import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.binding.ValueConversions;

public interface IValue {
    Object evalUnsafe(ExpressionEvaluator<?> evaluator);

    default float evalAsFloat(ExpressionEvaluator<?> evaluator) {
        return ValueConversions.asFloat(evalSafe(evaluator));
    }

    default int evalAsInt(ExpressionEvaluator<?> evaluator) {
        return ValueConversions.asInt(evalSafe(evaluator));
    }

    default boolean evalAsBoolean(ExpressionEvaluator<?> evaluator) {
        return ValueConversions.asBoolean(evalSafe(evaluator));
    }

    default Object evalSafe(ExpressionEvaluator<?> evaluator) {
        try {
            return evalUnsafe(evaluator);
        } catch (Throwable th) {
            reportFailure(evaluator, th);
            return null;
        }
    }

    static void reportFailure(ExpressionEvaluator<?> evaluator, Throwable error) {
        if (evaluator.entity()
                instanceof
                com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext<?> context)
            context.geoInstance().runtime().diagnostic("Expression evaluation: " + error);
    }
}
