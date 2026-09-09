package com.blanoir.moons.client.module.impl.render.chams;

import com.blanoir.moons.client.access.GameAccess;

import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Util;

import java.util.Set;
import java.util.function.Function;

/**
 * Remaps entity render types to the Chams offscreen target.
 * The original pipeline, textures, lighting and blend state are kept;
 * only the output target is replaced.
 */
public final class ChamsRenderLayers {
    private static final Set<String> SUPPORTED_RENDER_TYPES =
            Set.of(
                    "armor_cutout_no_cull",
                    "armor_decal_cutout_no_cull",
                    "armor_entity_glint",
                    "entity_translucent",
                    "entity_cutout",
                    "entity_cutout_cull",
                    "entity_cutout_no_cull",
                    "entity_solid",
                    "entity_glint",
                    "glint",
                    "glint_translucent",
                    "item_cutout",
                    "item_translucent");

    private static OutputTarget cachedTarget;
    private static Function<RenderType, RenderType> cachedLayers;

    private ChamsRenderLayers() {}

    public static boolean supports(RenderType renderType) {
        if (renderType == null) {
            return false;
        }

        return SUPPORTED_RENDER_TYPES.contains(renderLayerName(renderType));
    }

    public static synchronized RenderType remap(RenderType renderType, OutputTarget target) {
        if (renderType == null || target == null || renderType.outputTarget() == target) {
            return renderType;
        }
        if (cachedTarget != target || cachedLayers == null) {
            cachedTarget = target;
            cachedLayers = Util.memoize(layer -> copyToTarget(layer, target));
        }
        return cachedLayers.apply(renderType);
    }

    private static RenderType copyToTarget(RenderType baseLayer, OutputTarget target) {
        return GameAccess.copyRenderType(
                baseLayer, "moons_chams_" + renderLayerName(baseLayer), target);
    }

    private static String renderLayerName(RenderType renderType) {
        return GameAccess.renderTypeName(renderType);
    }
}
