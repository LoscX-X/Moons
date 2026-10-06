package com.blanoir.moons.loader.sodium.mapping;

import com.blanoir.moons.loader.common.mapping.MappingService;
import com.blanoir.moons.loader.common.mapping.TargetMethod;

import java.util.List;

/** Optional Sodium targets for Minecraft 26.3; missing targets do not become startup requirements. */
public final class SodiumMappings {
    private SodiumMappings() {}

    public static MappingService apply(MappingService common) {
        return common.withTargets(
                List.of(
                        new TargetMethod(
                                "optional.sodium.render-model",
                                List.of(
                                        "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer"),
                                List.of("renderModel"),
                                "(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
                                        + "Lnet/minecraft/world/level/block/state/BlockState;"
                                        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
                                TargetMethod.HookKind.SODIUM_RENDER_MODEL),
                        new TargetMethod(
                                "optional.sodium.process-quad",
                                List.of(
                                        "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer"),
                                List.of("processQuad"),
                                "(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;)V",
                                TargetMethod.HookKind.SODIUM_PROCESS_QUAD),
                        new TargetMethod(
                                "optional.sodium.world-render",
                                List.of(
                                        "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer"),
                                List.of("drawChunkLayer"),
                                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                                        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                                        + "DDDLcom/mojang/blaze3d/textures/GpuSampler;)V",
                                TargetMethod.HookKind.VOID_START_END_ARG0),
                        new TargetMethod(
                                "optional.sodium.clip-occlusion",
                                List.of(
                                        "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager"),
                                List.of("shouldUseOcclusionCulling"),
                                "(Lnet/minecraft/client/Camera;Z)Z",
                                TargetMethod.HookKind.BOOLEAN_RETURN_ARG)));
    }
}
