package com.blanoir.moons.client.render.model;

import com.blanoir.moons.client.access.GameAccess;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Marks model batches while retaining their pipeline, textures, lighting and blend state.
 * The original pipeline, textures, lighting and blend state are kept;
 * marked draws are replayed into the offscreen target.
 */
public final class ModelOverlayMaterials {
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
    private static final String OVERLAY_PREFIX = "moons_visual_model_";
    private static final Map<Integer, Map<RenderType, RenderType>> OVERLAY_LAYERS = new HashMap<>();
    private static final Map<RenderType, RenderType> SOURCES = new IdentityHashMap<>();

    private ModelOverlayMaterials() {}

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
            cachedLayers = Util.memoize(ModelOverlayMaterials::copyToTarget);
        }
        return cachedLayers.apply(renderType);
    }

    public static synchronized RenderType overlayModel(RenderType renderType, int outlineColor) {
        if (renderType == null) return null;
        Integer marked = outlineColor(renderLayerName(renderType));
        if (marked != null && marked.intValue() == outlineColor) return renderType;
        RenderType source = SOURCES.getOrDefault(renderType, renderType);
        return OVERLAY_LAYERS
                .computeIfAbsent(outlineColor, ignored -> new IdentityHashMap<>())
                .computeIfAbsent(
                        source,
                        layer -> {
                            RenderType copy =
                                    GameAccess.copyRenderType(
                                            layer,
                                            OVERLAY_PREFIX
                                                    + Integer.toUnsignedString(outlineColor, 16)
                                                    + "_"
                                                    + renderLayerName(layer));
                            SOURCES.put(copy, layer);
                            return copy;
                        });
    }

    /** The prepared name survives collection and delayed item/OIT draws without ambient state. */
    public static Integer outlineColor(String name) {
        if (!name.startsWith(OVERLAY_PREFIX)) return null;
        int end = name.indexOf('_', OVERLAY_PREFIX.length());
        if (end <= OVERLAY_PREFIX.length() || end - OVERLAY_PREFIX.length() > 8) return null;
        try {
            return Integer.parseUnsignedInt(name.substring(OVERLAY_PREFIX.length(), end), 16);
        } catch (NumberFormatException invalidMarker) {
            return null;
        }
    }

    public static synchronized void retainOverlayStyles(Set<Integer> active) {
        var iterator = OVERLAY_LAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!active.contains(entry.getKey())) {
                entry.getValue().values().forEach(SOURCES::remove);
                iterator.remove();
            }
        }
    }

    private static RenderType copyToTarget(RenderType baseLayer) {
        return GameAccess.copyRenderType(baseLayer, "moons_chams_" + renderLayerName(baseLayer));
    }

    private static String renderLayerName(RenderType renderType) {
        return GameAccess.renderTypeName(renderType);
    }

    public static void clear() {
        cachedLayers = null;
        OVERLAY_LAYERS.clear();
        SOURCES.clear();
    }
}
