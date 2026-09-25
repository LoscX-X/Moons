package com.blanoir.moons.client.render.model;

import com.blanoir.moons.client.access.GameAccess;

import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;

import org.joml.Matrix4fStack;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Marks eligible batches without redirecting their normal draw. A draw hook copies
 * the same geometry into the visual target; exclusive ghost batches skip the normal draw.
 */
public final class ModelOverlayMaterials {
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

    private static final Map<OutputTarget, Map<RenderType, RenderType>> LAYERS =
            new IdentityHashMap<>();
    private static final Map<OutputTarget, Map<RenderType, RenderType>> EXCLUSIVE_LAYERS =
            new IdentityHashMap<>();
    private static final Map<OutputTarget, Capture> CAPTURES = new IdentityHashMap<>();

    public record Capture(
            RenderType layer, Consumer<Matrix4fStack> layeringModifier, boolean exclusive) {}

    private ModelOverlayMaterials() {}

    public static boolean supports(RenderType renderType) {
        if (renderType == null) {
            return false;
        }

        return SUPPORTED_RENDER_TYPES.contains(renderLayerName(renderType));
    }

    public static synchronized RenderType remap(RenderType renderType, OutputTarget target) {
        return remap(renderType, target, false);
    }

    public static synchronized RenderType remap(
            RenderType renderType, OutputTarget target, boolean exclusive) {
        if (renderType == null
                || target == null
                || CAPTURES.containsKey(renderType.outputTarget())) {
            return renderType;
        }
        return (exclusive ? EXCLUSIVE_LAYERS : LAYERS)
                .computeIfAbsent(target, ignored -> new IdentityHashMap<>())
                .computeIfAbsent(renderType, layer -> mark(layer, target, exclusive));
    }

    private static RenderType mark(RenderType base, OutputTarget target, boolean exclusive) {
        String name = (exclusive ? "moons_visual_model_" : "moons_chams_") + renderLayerName(base);
        OutputTarget marker = new OutputTarget(name, base.outputTarget()::getRenderTarget);
        // Own the layering scope: vanilla's draw only pops its matrix on the successful path.
        RenderType capture =
                GameAccess.copyRenderType(
                        base, name + "_capture", target, LayeringTransform.NO_LAYERING);
        CAPTURES.put(
                marker,
                new Capture(
                        capture,
                        GameAccess.renderTypeLayeringTransform(base).getModifier(),
                        exclusive));
        return GameAccess.copyRenderType(base, name, marker);
    }

    public static Capture capture(OutputTarget marker) {
        return CAPTURES.get(marker);
    }

    public static void clear() {
        LAYERS.clear();
        EXCLUSIVE_LAYERS.clear();
        CAPTURES.clear();
    }

    public static void forgetTarget(OutputTarget target) {
        LAYERS.remove(target);
        EXCLUSIVE_LAYERS.remove(target);
        CAPTURES.values().removeIf(capture -> capture.layer().outputTarget() == target);
    }

    private static String renderLayerName(RenderType renderType) {
        return GameAccess.renderTypeName(renderType);
    }
}
