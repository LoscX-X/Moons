package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.LocalYsmModel;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

/** World-target translucent culling; the vanilla culled item target is unsuitable for the body. */
final class YsmRenderTypes {
    private final YsmOpaqueEmissive opaqueEmissive = new YsmOpaqueEmissive();
    private static final java.lang.reflect.Method CREATE = findCreate();
    private static final RenderPipeline TRANSLUCENT_CULL =
            RenderPipeline.builder(entitySnippet())
                    .withLocation(
                            Identifier.fromNamespaceAndPath(
                                    "moons", "pipeline/ysm_translucent_cull"))
                    .withShaderDefine("ALPHA_CUTOUT", .1f)
                    .withShaderDefine("PER_FACE_LIGHTING")
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
                    .withCull(true)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .build();
    private Identifier texture;
    private RenderType culled;

    RenderType get(Identifier texture, LocalYsmModel.Pass pass) {
        if (pass.glow())
            return pass.translucent()
                    ? RenderTypes.entityTranslucentEmissive(texture)
                    : opaqueEmissive.get(texture);
        if (!pass.translucent())
            return pass.cull()
                    ? RenderTypes.entityCutoutCull(texture)
                    : RenderTypes.entityCutout(texture);
        if (!pass.cull()) return RenderTypes.entityTranslucent(texture);
        if (!texture.equals(this.texture)) {
            this.texture = texture;
            RenderSetup setup =
                    RenderSetup.builder(TRANSLUCENT_CULL)
                            .withTexture("Sampler0", texture)
                            .useLightmap()
                            .useOverlay()
                            .sortOnUpload()
                            .createRenderSetup();
            try {
                culled = (RenderType) CREATE.invoke(null, "moons_ysm_translucent_cull", setup);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Could not create YSM render type", error);
            }
        }
        return culled;
    }

    // Upstream exposes these with a Fabric access widener. Resolve them only in this version
    // adapter.
    private static RenderPipeline.Snippet entitySnippet() {
        try {
            var field = RenderPipelines.class.getDeclaredField("ENTITY_SNIPPET");
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
