package com.blanoir.tempos.client.render.world;

import com.blanoir.tempos.api.ScopedResources;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.frame.WorldRenderDispatch;
import com.blanoir.tempos.runtime.lifecycle.DefaultResourceScope;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses the real RenderPearl encoder guard and close implementation, without a GPU or window. */
public final class WorldRenderPassVerification {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Class<?> encoderType = Class.forName("com.mojang.renderpearl.frontend.FrontendCommandEncoder");
        Class<?> passType = Class.forName("com.mojang.renderpearl.frontend.FrontendRenderPass");
        Object encoder = unsafe.allocateInstance(encoderType), pass = unsafe.allocateInstance(passType);
        var active = encoderType.getDeclaredField("isInRenderPass"); active.setAccessible(true);
        var onFinish = passType.getDeclaredField("onFinish"); onFinish.setAccessible(true);
        onFinish.set(pass, (Runnable) () -> {
            try { active.setBoolean(encoder, false); } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        var create = encoderType.getMethod("createRenderPass", Class.forName("com.mojang.renderpearl.api.commands.RenderPassDescriptor"));
        var calls = new AtomicInteger();
        var rejected = new AtomicBoolean();
        try (var scope = new DefaultResourceScope()) {
            ScopedResources.run(scope, () -> EventBus.WORLD_RENDER.register("verify.pass-order", event -> {
                calls.incrementAndGet();
                try { create.invoke(encoder, new Object[]{null}); }
                catch (InvocationTargetException failure) {
                    if (String.valueOf(failure.getCause().getMessage()).contains("Close the existing render pass")) rejected.set(true);
                    // A null descriptor deliberately stops before any GPU/backend work after the guard.
                } catch (Exception failure) { throw new RuntimeException(failure); }
            }));
            WorldRenderDispatch.beginFrame();
            active.setBoolean(encoder, true);
            try {
                WorldRenderDispatch.class.getMethod("defer", Object.class, PoseStack.class, float.class).invoke(null, pass, new PoseStack(), .5f);
            } catch (NoSuchMethodException oldImmediateDispatch) {
                WorldRenderDispatch.post(new PoseStack(), .5f);
            }
            require(!rejected.get(), "Sodium world callback opened a second render pass while terrain pass was active");
            require(calls.get() == 0, "World overlays were not deferred");
            var closed = WorldRenderDispatch.class.getMethod("afterPass", Object.class);
            closed.invoke(null, new Object());
            require(calls.get() == 0, "An unrelated pass flushed world overlays");
            passType.getMethod("close").invoke(pass);
            require(!active.getBoolean(encoder), "Real pass.close did not release encoder");
            closed.invoke(null, pass);
            require(calls.get() == 1 && !rejected.get(), "Closed pass did not flush exactly once");
            closed.invoke(null, pass);
            require(calls.get() == 1, "Repeated close drew overlays twice");
            WorldRenderDispatch.beginFrame();
            WorldRenderDispatch.class.getMethod("defer", Object.class, PoseStack.class, float.class).invoke(null, pass, new PoseStack(), 0f);
            WorldRenderDispatch.beginFrame();
            closed.invoke(null, pass);
            require(calls.get() == 1, "A previous frame retained a pending pass");
        }
        System.out.println("TEMPOS_WORLD_RENDER_PASS_VERIFIED real-encoder-guard real-pass-close deferred-owner frame-reset once-per-frame");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
