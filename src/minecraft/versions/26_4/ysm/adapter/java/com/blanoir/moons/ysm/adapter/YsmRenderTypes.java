package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.LocalYsmModel;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** 26.4-snapshot-1 supplies world entity materials with matching RenderPearl/OIT pipelines. */
final class YsmRenderTypes {
    private final YsmOpaqueEmissive opaqueEmissive = new YsmOpaqueEmissive();

    RenderType get(Identifier texture, LocalYsmModel.Pass pass) {
        if (pass.glow())
            return pass.translucent()
                    ? RenderTypes.entityTranslucentEmissive(texture)
                    : opaqueEmissive.get(texture);
        if (!pass.translucent())
            return pass.cull()
                    ? RenderTypes.entityCutoutCull(texture)
                    : RenderTypes.entityCutout(texture);
        return pass.cull()
                ? RenderTypes.entityTranslucentCull(texture)
                : RenderTypes.entityTranslucent(texture);
    }
}
