package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.render.item.NativeItemIconCapture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;

/** Builds a GUI preview from the actual block-state model, including multipart geometry. */
public final class PluginBlockPreviewModel {
    private PluginBlockPreviewModel() {}

    public static TrackingItemStackRenderState create(BlockState block) {
        var client = Minecraft.getInstance();
        var model = client.getModelManager().getBlockModelShaper().getBlockModel(block);
        var state = new TrackingItemStackRenderState();
        // A model-less stack initializes GUI context without adding the carrier's item geometry.
        var context = new ItemStack(Items.STONE);
        context.remove(DataComponents.ITEM_MODEL);
        client.getItemModelResolver()
                .updateForTopItem(
                        state, context, ItemDisplayContext.GUI, client.level, client.player, 0);
        state.appendModelIdentityElement(block);
        state.appendModelIdentityElement(model);
        var parts = new ArrayList<BlockModelPart>();
        model.collectParts(RandomSource.create(42), parts);
        var quads = new ArrayList<BakedQuad>();
        for (var part : parts) {
            quads.addAll(part.getQuads(null));
            for (Direction direction : Direction.values()) quads.addAll(part.getQuads(direction));
        }
        if (quads.isEmpty()) return state;
        var layer = state.newLayer();
        NativeItemIconCapture.setQuads(layer, quads);
        layer.setParticleIcon(model.particleIcon());
        layer.setRenderType(
                net.minecraft.client.renderer.rendertype.RenderTypes.itemEntityTranslucentCull(
                        net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS));
        layer.setUsesBlockLight(true);
        // Fit large custom models as well as ordinary cubes inside the thumbnail.
        var minimum = new Vector3f(Float.POSITIVE_INFINITY);
        var maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        var extents = new ArrayList<Vector3fc>();
        int maxTint = -1;
        for (var quad : quads) {
            for (int vertex = 0; vertex < 4; vertex++) {
                var point = new Vector3f(quad.position(vertex));
                minimum.min(point);
                maximum.max(point);
                extents.add(point);
            }
            maxTint = Math.max(maxTint, quad.tintIndex());
        }
        Vector3f center = new Vector3f(minimum).add(maximum).mul(0.5f);
        Vector3f dimensions = new Vector3f(maximum).sub(minimum);
        float scale =
                0.58f / Math.max(1f, Math.max(dimensions.x, Math.max(dimensions.y, dimensions.z)));
        layer.setTransform(
                new ItemTransform(
                        new Vector3f(30, 225, 0),
                        new Vector3f(0.5f).sub(center).mul(scale),
                        new Vector3f(scale)));
        layer.setExtents(() -> extents.toArray(Vector3fc[]::new));
        int[] tints = layer.prepareTintLayers(Math.min(maxTint, 255) + 1);
        for (int tint = 0; tint < tints.length; tint++) {
            int rgb = client.getBlockColors().getColor(block, null, null, tint);
            tints[tint] = rgb | 0xff000000;
        }
        return state;
    }
}
