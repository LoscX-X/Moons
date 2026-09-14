package com.blanoir.moons.ysm.internal.client.animation.molang;

import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.Variable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ArgsVariable implements Variable {

    public static final ArgsVariable INSTANCE = new ArgsVariable();

    @Override
    @Nullable
    public Object evaluate(@NotNull ExecutionContext<?> context) {
        Object entity = context.entity();
        if (entity instanceof IContext) {
            return ((IContext<?>) entity).getAnimationLayers();
        }
        return null;
    }
}
