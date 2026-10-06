package com.blanoir.moons.client.render.model;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Runs the built-in outline effect exclusively against an explicitly supplied visual target. */
public final class VisualModelOutline {
    private static final Identifier OUTLINE =
            Identifier.fromNamespaceAndPath("minecraft", "entity_outline");
    private static final Set<Identifier> EXTERNAL_TARGETS = Set.of(OUTLINE);
    private static OutlinePool pool;
    private static Object device;
    private static boolean warned;

    private VisualModelOutline() {}

    /** Replaces the supplied mask with straight-alpha outline pixels; false keeps only the body. */
    public static boolean apply(RenderTarget target) {
        if (target == null
                || target.width <= 0
                || target.height <= 0
                || target.getColorTextureView() == null) return false;
        boolean applied = false;
        OutlinePool framePool = null;
        var projection = RenderSystem.getProjectionMatrixBuffer();
        var projectionType = RenderSystem.getProjectionType();
        try {
            var currentDevice = RenderSystem.getDevice();
            if (device != currentDevice) {
                close();
                device = currentDevice;
            }
            // ShaderManager owns and reloads this cached chain. Never retain or close it here.
            PostChain chain =
                    Minecraft.getInstance()
                            .getShaderManager()
                            .getPostChain(OUTLINE, EXTERNAL_TARGETS);
            if (chain == null) {
                warn("The entity outline post effect is unavailable", null);
            } else {
                if (pool == null) pool = new OutlinePool();
                framePool = pool;
                var graph = new FrameGraphBuilder();
                var input = graph.importExternal("moons extra model outline", target);
                chain.addToFrame(
                        graph,
                        target.width,
                        target.height,
                        PostChain.TargetBundle.of(OUTLINE, input));
                graph.execute(framePool);
                applied = true;
            }
        } catch (RuntimeException failure) {
            warn("Skipping the extra model outline", failure);
        } finally {
            // PostPass restores this only on success; preserve the caller's projection on failure.
            try {
                RenderSystem.setProjectionMatrix(projection, projectionType);
            } catch (RuntimeException failure) {
                applied = false;
                warn("Unable to restore the outline projection", failure);
            }
            if (framePool != null) {
                try {
                    framePool.endFrame();
                } catch (RuntimeException failure) {
                    applied = false;
                    warn("Unable to finish the extra model outline resources", failure);
                }
            }
        }
        return applied;
    }

    public static void close() {
        var previous = pool;
        pool = null;
        device = null;
        warned = false;
        if (previous != null) previous.close();
    }

    private static void warn(String message, RuntimeException failure) {
        if (warned) return;
        warned = true;
        var logger = System.getLogger(VisualModelOutline.class.getName());
        if (failure == null) logger.log(System.Logger.Level.WARNING, message);
        else logger.log(System.Logger.Level.WARNING, message, failure);
    }

    /** FrameGraphBuilder does not release acquired targets when a pass throws. */
    private static final class OutlinePool extends CrossFrameResourcePool {
        private final Map<Object, Lease<?>> outstanding = new IdentityHashMap<>();

        OutlinePool() {
            super(3);
        }

        @Override
        public <T> T acquire(ResourceDescriptor<T> descriptor) {
            T value = super.acquire(descriptor);
            outstanding.put(value, new Lease<>(descriptor, value));
            return value;
        }

        @Override
        public <T> void release(ResourceDescriptor<T> descriptor, T value) {
            super.release(descriptor, value);
            outstanding.remove(value);
        }

        @Override
        public void endFrame() {
            releaseOutstanding();
            super.endFrame();
        }

        @Override
        public void close() {
            releaseOutstanding();
            super.close();
        }

        private void releaseOutstanding() {
            var iterator = outstanding.values().iterator();
            while (iterator.hasNext()) {
                Lease<?> lease = iterator.next();
                iterator.remove();
                lease.release(this);
            }
        }
    }

    private record Lease<T>(ResourceDescriptor<T> descriptor, T value) {
        void release(CrossFrameResourcePool pool) {
            pool.release(descriptor, value);
        }
    }
}
