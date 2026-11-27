package com.blanoir.tempos.ysm.adapter;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.*;

import net.minecraft.client.renderer.pipeline.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** Fully opaque emissive faces retain the emissive shader without blending or sorting. */
final class YsmOpaqueEmissive {
    private static final java.lang.reflect.Method CREATE = findCreate();
    private static final RenderPipeline PIPELINE = opaquePipeline();
    private Identifier texture;
    private RenderType type;

    RenderType get(Identifier requested) {
        if (requested.equals(texture)) return type;
        var setup =
                RenderSetup.nonOitBuilder(PIPELINE)
                        .withTexture("Sampler0", requested)
                        .useOverlay()
                        .createRenderSetup();
        try {
            var created = (RenderType) CREATE.invoke(null, "tempos_ysm_emissive_opaque", setup);
            texture = requested;
            type = created;
            return type;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not create opaque emissive YSM material", error);
        }
    }

    private static RenderPipeline opaquePipeline() {
        var emissive = net.minecraft.client.renderer.pipeline.variant.EntityRenderPipelineVariant.TRANSLUCENT_EMISSIVE;
        var opaque = new net.minecraft.client.renderer.pipeline.variant.EntityRenderPipelineVariant(
                "tempos_ysm_emissive_opaque", ColorTargetState.DEFAULT, DepthStencilState.DEFAULT,
                emissive.lighting(), false, emissive.hasOverlay(), true, false,
                emissive.hasGlint(), emissive.isEmissive(), emissive.applyTextureMatrix(),
                emissive.dissolve(), emissive.additive());
        return RenderPipelines.entity(new RenderPipelines.EntityParameters(opaque, null));
    }

    private static java.lang.reflect.Method findCreate() {
        try {
            var method =
                    RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
