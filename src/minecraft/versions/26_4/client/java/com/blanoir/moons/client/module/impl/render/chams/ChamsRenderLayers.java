package com.blanoir.moons.client.module.impl.render.chams;

import com.blanoir.moons.client.access.GameAccess;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Util;

import java.util.Set;
import java.util.function.Function;

/**
 * Remaps entity render types to the Chams offscreen target.
 * The original pipeline, textures, lighting and blend state are kept;
 * marked draws are replayed into the offscreen target.
 */
public final class ChamsRenderLayers {
    private static final Set<String> SUPPORTED_RENDER_TYPES =
            Set.of(
                    "armor_cutout_no_cull",
                    "armor_cutout_no_cull_glint",
                    "armor_trim",
                    "armor_trim_decal",
                    "trimmed_armor_glint",
                    "entity_translucent",
                    "entity_translucent_cull_item_target",
                    "entity_cutout",
                    "entity_cutout_cull",
                    "entity_cutout_z_offset",
                    "entity_solid",
                    "entity_solid_glint",
                    "entity_solid_z_offset_forward",
                    "patterned_shield_glint",
                    "item_cutout",
                    "item_cutout_glint",
                    "item_cutout_glint_special",
                    "item_translucent",
                    "item_translucent_glint",
                    "item_translucent_glint_special");

    private static Function<RenderType, RenderType> cachedLayers;

    private ChamsRenderLayers() {}

    public static boolean supports(RenderType renderType) {
        if (renderType == null) {
            return false;
        }

        return SUPPORTED_RENDER_TYPES.contains(renderLayerName(renderType));
    }

    public static synchronized RenderType remap(RenderType renderType) {
        if (renderType == null || renderLayerName(renderType).startsWith("moons_chams_")) {
            return renderType;
        }
        if (cachedLayers == null) {
            cachedLayers = Util.memoize(ChamsRenderLayers::copyToTarget);
        }
        return cachedLayers.apply(renderType);
    }

    private static RenderType copyToTarget(RenderType baseLayer) {
        return GameAccess.copyRenderType(baseLayer, "moons_chams_" + renderLayerName(baseLayer));
    }

    private static String renderLayerName(RenderType renderType) {
        return GameAccess.renderTypeName(renderType);
    }
}
