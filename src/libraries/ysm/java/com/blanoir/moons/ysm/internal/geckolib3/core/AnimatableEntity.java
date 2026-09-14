package com.blanoir.moons.ysm.internal.geckolib3.core;

import com.blanoir.moons.ysm.internal.client.animation.molang.PhysicsManager;
import com.blanoir.moons.ysm.internal.geckolib3.core.builder.*;
import com.blanoir.moons.ysm.internal.geckolib3.core.manager.AnimationData;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.value.IValue;

import java.util.List;

/** Host boundary of the upstream animation engine. No game objects cross this boundary. */
public abstract class AnimatableEntity<T> {
    public abstract Animation getAnimation(String name);

    public abstract AnimationController getAnimationEntries(String name);

    public abstract AnimationData getAnimationData();

    public abstract PhysicsManager getPhysicsManager();

    public abstract IValue resolveExpression(String name);

    public abstract List<IValue> getRenderLayers();

    public abstract void setAnimationState(
            String name, com.blanoir.moons.ysm.internal.geckolib3.core.enums.AnimationState state);

    public abstract boolean isSwinging();

    public com.blanoir.moons.ysm.internal.geckolib3.core.processor.IBone getBone(int id) {
        return runtime()
                .bone(
                        com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool
                                .getString(id));
    }

    public abstract com.blanoir.moons.ysm.internal.runtime.LocalRuntime runtime();
}
