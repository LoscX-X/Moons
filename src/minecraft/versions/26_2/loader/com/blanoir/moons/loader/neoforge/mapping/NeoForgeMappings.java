package com.blanoir.moons.loader.neoforge.mapping;

import com.blanoir.moons.loader.common.mapping.InjectionPoint;
import com.blanoir.moons.loader.common.mapping.MappingService;

import java.util.List;

/** Exact NeoForge-only signature variants for Minecraft 26.2. */
public final class NeoForgeMappings {
    private NeoForgeMappings() {}

    public static MappingService apply(MappingService common) {
        return common.withDescriptors(
                        "client.world-render",
                        InjectionPoint.Environment.NEOFORGE,
                        mainPassDescriptors())
                .withDescriptors(
                        "render.chams-frame",
                        InjectionPoint.Environment.NEOFORGE,
                        mainPassDescriptors())
                .withDescriptors(
                        "render.ysm-right-hand",
                        InjectionPoint.Environment.NEOFORGE,
                        handDescriptors())
                .withDescriptors(
                        "render.ysm-left-hand",
                        InjectionPoint.Environment.NEOFORGE,
                        handDescriptors());
    }

    private static List<String> handDescriptors() {
        String prefix =
                "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                        + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                        + "ILnet/minecraft/resources/Identifier;Z";
        return List.of(prefix + "Lnet/minecraft/world/entity/Avatar;)V");
    }

    private static List<String> mainPassDescriptors() {
        String prefix =
                "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                        + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                        + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                        + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
        String suffix =
                "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                        + "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                        + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                        + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                        + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                        + "Lcom/mojang/blaze3d/resource/ResourceHandle;)V";
        // NeoForge captures the model-view matrix after the terrain arguments.
        // Earlier argument slots used by both hook installers remain unchanged.
        return List.of(prefix + "Lorg/joml/Matrix4fc;" + suffix);
    }
}
