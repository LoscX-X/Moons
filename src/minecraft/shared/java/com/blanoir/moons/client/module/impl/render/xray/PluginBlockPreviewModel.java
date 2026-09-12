package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.utils.render.NativeItemIconCapture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
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
        var model = client.getModelManager().getBlockStateModelSet().get(block);
        var state = new TrackingItemStackRenderState();
        // A model-less stack initializes GUI context without adding the carrier's item geometry.
        var context = new ItemStack(Items.STONE);
        context.remove(DataComponents.ITEM_MODEL);
        client.getItemModelResolver()
                .updateForTopItem(
                        state, context, ItemDisplayContext.GUI, client.level, client.player, 0);
        state.appendModelIdentityElement(block);
        state.appendModelIdentityElement(model);
        var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(RandomSource.create(42), parts);
        var quads = new ArrayList<BakedQuad>();
        for (var part : parts) {
            quads.addAll(part.getQuads(null));
            for (Direction direction : Direction.values()) quads.addAll(part.getQuads(direction));
        }
        if (quads.isEmpty()) return state;
        var layer = state.newLayer();
        NativeItemIconCapture.setQuads(layer, quads);
        layer.setParticleMaterial(model.particleMaterial());
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
            maxTint = Math.max(maxTint, quad.materialInfo().tintIndex());
        }
        Vector3f center = new Vector3f(minimum).add(maximum).mul(0.5f);
        Vector3f dimensions = new Vector3f(maximum).sub(minimum);
        float scale =
                0.58f / Math.max(1f, Math.max(dimensions.x, Math.max(dimensions.y, dimensions.z)));
        layer.setItemTransform(
                new ItemTransform(new Vector3f(30, 225, 0), new Vector3f(), new Vector3f(scale)));
        layer.setLocalTransform(
                new org.joml.Matrix4f().translation(new Vector3f(0.5f).sub(center)));
        layer.setExtents(() -> extents.toArray(Vector3fc[]::new));
        for (int tint = 0; tint <= Math.min(maxTint, 255); tint++) {
            var source = client.getBlockColors().getTintSource(block, tint);
            int rgb = source == null ? 0xffffff : source.color(block);
            layer.tintLayers().add(rgb | 0xff000000);
        }
        return state;
    }
}
