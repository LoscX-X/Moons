package com.blanoir.tempos.loader.compat.neoforge;

import com.blanoir.tempos.loader.mapping.InjectionPoint;
import com.blanoir.tempos.loader.mapping.MappingService;

import java.util.List;

/** Exact descriptors from the NeoForge-patched Minecraft 26.1.2 client. */
public final class NeoForgeMappings {
    private NeoForgeMappings() {}

    public static MappingService apply(MappingService common) {
        return common.withDescriptors("client.world-render", InjectionPoint.Environment.NEOFORGE, mainPassDescriptors())
                .withDescriptors("render.chams-frame", InjectionPoint.Environment.NEOFORGE, mainPassDescriptors())
                .withDescriptors("render.ysm-right-hand", InjectionPoint.Environment.NEOFORGE, handDescriptors())
                .withDescriptors("render.ysm-left-hand", InjectionPoint.Environment.NEOFORGE, handDescriptors());
    }

    private static List<String> handDescriptors() {
        return List.of("(Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z"
                + "Lnet/minecraft/client/player/AbstractClientPlayer;)V");
    }

    private static List<String> mainPassDescriptors() {
        return List.of("(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lorg/joml/Matrix4fc;"
                + "Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;"
                + "Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;"
                + "Lcom/mojang/blaze3d/resource/ResourceHandle;Z)V");
    }
}
