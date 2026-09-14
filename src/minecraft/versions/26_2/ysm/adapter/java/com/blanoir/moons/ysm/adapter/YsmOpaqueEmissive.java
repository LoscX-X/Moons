package com.blanoir.moons.ysm.adapter;

import com.mojang.blaze3d.pipeline.*;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** Fully opaque emissive faces retain the emissive shader without blending or sorting. */
final class YsmOpaqueEmissive {
    private static final java.lang.reflect.Method CREATE = findCreate();
    private static final RenderPipeline PIPELINE =
            RenderPipeline.builder(emissiveSnippet())
                    .withLocation(
                            Identifier.fromNamespaceAndPath(
                                    "moons", "pipeline/ysm_emissive_opaque"))
                    .withShaderDefine("ALPHA_CUTOUT", .1f)
                    .withShaderDefine("PER_FACE_LIGHTING")
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .withCull(false)
                    .build();
    private Identifier texture;
    private RenderType type;

    RenderType get(Identifier requested) {
        if (requested.equals(texture)) return type;
        var setup =
                RenderSetup.builder(PIPELINE)
                        .withTexture("Sampler0", requested)
                        .useOverlay()
                        .createRenderSetup();
        try {
            var created = (RenderType) CREATE.invoke(null, "moons_ysm_emissive_opaque", setup);
            texture = requested;
            type = created;
            return type;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not create opaque emissive YSM material", error);
        }
    }

    private static RenderPipeline.Snippet emissiveSnippet() {
        try {
            var field = RenderPipelines.class.getDeclaredField("ENTITY_EMISSIVE_SNIPPET");
            field.setAccessible(true);
            return (RenderPipeline.Snippet) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
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
