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
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.molang.runtime.Function;
import com.blanoir.moons.ysm.internal.util.log.ILogger;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

public class AnimationContext<TEntity> implements IContext<TEntity> {

    public final TEntity entity;

    public final AnimatableEntity<?> instance;

    public final AnimationEvent<?> animationEvent;

    public final EntityModelData data;

    public AnimationControllerContext animationControllerContext;

    public PlaybackFlags playbackFlags;

    public AudioPlayerManager audioPlayerManager;

    public Random random;

    public VariableStorage storage;

    public IForeignVariableStorage foreignStorage;

    private ILogger logger;

    private boolean isClientSide;

    public AnimationContext(
            TEntity entity,
            AnimatableEntity<?> instance,
            AnimationEvent<?> animationEvent,
            EntityModelData data) {
        this.entity = entity;
        this.instance = instance;
        this.animationEvent = animationEvent;
        this.data = data;
    }

    private AnimationContext(TEntity entity, AnimationContext<?> context) {
        this.entity = entity;
        this.instance = context.instance;
        this.animationEvent = context.animationEvent;
        this.data = context.data;
        this.animationControllerContext = context.animationControllerContext;
        this.random = context.random;
        this.storage = context.storage;
        this.audioPlayerManager = context.audioPlayerManager;
        this.foreignStorage = context.foreignStorage;
    }

    @Override
    public AnimationEvent<?> animationEvent() {
        return this.animationEvent;
    }

    @Override
    public AnimatableEntity<?> geoInstance() {
        return this.instance;
    }

    @Override
    public EntityModelData data() {
        return this.data;
    }

    @Override
    public AnimationControllerContext animationControllerContext() {
        return this.animationControllerContext;
    }

    @Override
    public PlaybackFlags getPlaybackFlags() {
        return this.playbackFlags;
    }

    @Override
    public Random random() {
        return this.random;
    }

    @Override
    public TEntity entity() {
        return this.entity;
    }

    @Override
    public <TChild> IContext<TChild> createChild(TChild child) {
        return new AnimationContext<>(child, this);
    }

    @Override
    public ITempVariableStorage tempStorage() {
        return this.storage.getLocalVariables();
    }

    @Override
    public IScopedVariableStorage scopedStorage() {
        return this.storage;
    }

    @Override
    public IForeignVariableStorage foreignStorage() {
        return this.foreignStorage;
    }

    @Override
    @Nullable
    public IControllerVariableStorage controllerStorage() {
        return this.animationControllerContext;
    }

    @Override
    @Nullable
    public IValue resolveExpression(String str) {
        return this.instance.resolveExpression(str);
    }

    @Override
    public Object callFunction(ExecutionContext<?> context, IValue value, List<?> list) {
        if (this.storage.getLocalVariables().pushScope(list)) {
            try {
                Object objMo1908xe6e508ff = value.evalSafe((ExpressionEvaluator) context);
                this.storage.getLocalVariables().popScope();
                return objMo1908xe6e508ff;
            } catch (Throwable th) {
                this.storage.getLocalVariables().popScope();
                throw th;
            }
        }
        return null;
    }

    @Override
    public Object callFunctionWithArgs(
            ExecutionContext<?> context, IValue value, Function.ArgumentCollection arguments) {
        if (this.storage.getLocalVariables().pushScopeWithArgs(context, arguments)) {
            try {
                Object objMo1908xe6e508ff = value.evalSafe((ExpressionEvaluator) context);
                this.storage.getLocalVariables().popScope();
                return objMo1908xe6e508ff;
            } catch (Throwable th) {
                this.storage.getLocalVariables().popScope();
                throw th;
            }
        }
        return null;
    }

    @Override
    public List<?> getAnimationLayers() {
        return this.storage.getLocalVariables().asList();
    }

    @Override
    public boolean isDebugMode() {
        return this.logger != null;
    }

    @Override
    public boolean isClientSide() {
        return this.isClientSide;
    }

    public void setIsClientSide(boolean z) {
        this.isClientSide = z;
    }

    @Override
    public void logWarning(String str, Object... objArr) {
        if (isDebugMode()) {
            this.logger.logFormatted(str, objArr);
        }
    }

    @Override
    public void logWarningComponent(String component) {
        if (isDebugMode()) {
            this.logger.logComponent(component);
        }
    }

    @Override
    @Nullable
    public AudioPlayerManager getAudioPlayerManager(boolean global) {
        AudioPlayerManager audioPlayerManager1;
        AudioPlayerManager audioPlayerManager2;
        if (!global) {
            if (this.animationControllerContext != null
                    && (audioPlayerManager2 =
                                    this.animationControllerContext.getAudioPlayerManager())
                            != null) {
                return audioPlayerManager2;
            }
            if (this.playbackFlags != null
                    && (audioPlayerManager1 = this.playbackFlags.getAudioPlayerManager()) != null) {
                return audioPlayerManager1;
            }
        }
        return this.audioPlayerManager;
    }

    public void setAudioPlayerManager(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    public void setAnimationControllerContext(AnimationControllerContext context) {
        this.animationControllerContext = context;
    }

    public void setPlaybackFlags(PlaybackFlags playbackFlags2) {
        this.playbackFlags = playbackFlags2;
    }

    public void setStorage(VariableStorage variableStorage) {
        this.storage = variableStorage;
        this.foreignStorage = variableStorage;
    }

    public void setRandom(Random random) {
        this.random = random;
    }

    public void setLogger(ILogger logger) {
        this.logger = logger;
    }
}
