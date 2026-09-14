package com.blanoir.moons.ysm.internal.geckolib3.core.molang.context;

import com.blanoir.moons.ysm.internal.audio.AudioPlayerManager;
import com.blanoir.moons.ysm.internal.audio.PlaybackFlags;
import com.blanoir.moons.ysm.internal.geckolib3.core.AnimatableEntity;
import com.blanoir.moons.ysm.internal.geckolib3.core.controller.AnimationControllerContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.event.predicate.AnimationEvent;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.storage.*;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.value.IValue;
import com.blanoir.moons.ysm.internal.geckolib3.model.provider.data.EntityModelData;
import com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

public interface IContext<TEntity> {
    TEntity entity();

    AnimatableEntity<?> geoInstance();

    AnimationEvent<?> animationEvent();

    EntityModelData data();

    @Nullable
    AnimationControllerContext animationControllerContext();

    @Nullable
    PlaybackFlags getPlaybackFlags();

    Random random();

    <TChild> IContext<TChild> createChild(TChild tchild);

    ITempVariableStorage tempStorage();

    IScopedVariableStorage scopedStorage();

    @Nullable
    IControllerVariableStorage controllerStorage();

    IForeignVariableStorage foreignStorage();

    @Nullable
    IValue resolveExpression(String str);

    Object callFunction(ExecutionContext<?> context, IValue value, List<?> list);

    Object callFunctionWithArgs(
            ExecutionContext<?> context, IValue value, Function.ArgumentCollection arguments);

    List<?> getAnimationLayers();

    boolean isDebugMode();

    boolean isClientSide();

    void logWarning(String str, Object... objArr);

    void logWarningComponent(String component);

    AudioPlayerManager getAudioPlayerManager(boolean global);
}
