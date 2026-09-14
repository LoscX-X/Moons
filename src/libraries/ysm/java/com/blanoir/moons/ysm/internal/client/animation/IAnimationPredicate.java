package com.blanoir.moons.ysm.internal.client.animation;

import com.blanoir.moons.ysm.internal.geckolib3.core.AnimatableEntity;
import com.blanoir.moons.ysm.internal.geckolib3.core.builder.ILoopType;
import com.blanoir.moons.ysm.internal.geckolib3.core.enums.PlayState;
import com.blanoir.moons.ysm.internal.geckolib3.core.event.predicate.AnimationEvent;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface IAnimationPredicate<T extends AnimatableEntity<?>> {
    PlayState predicate(AnimationEvent<T> event, ExpressionEvaluator<?> evaluator);

    @NotNull
    static <T extends AnimatableEntity<?>> PlayState playAnimationWithLoop(
            AnimationEvent<T> event, String animationName, ILoopType loopType) {
        event.getController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }

    @NotNull
    static <P extends AnimatableEntity<?>> PlayState predicate(
            AnimationEvent<P> event, String animationName) {
        event.getController().setAnimation(animationName);
        return PlayState.CONTINUE;
    }

    @NotNull
    static <T extends AnimatableEntity<?>> PlayState playLoopAnimation(
            AnimationEvent<T> event, String str) {
        return playAnimationWithLoop(event, str, ILoopType.EDefaultLoopTypes.LOOP);
    }
}
