package com.blanoir.moons.agent.core;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Exact logical-to-runtime targets for Minecraft 26.1.2. */
final class    VersionMappings {
    private VersionMappings() { }

    static String version() {
        return "26.1.2";
    }

    static MappingService create() {
        return new MappingService(List.of(
                new TargetMethod(
                        "client.tick",
                        List.of("net/minecraft/client/Minecraft"),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.CLIENT_TICK
                ),
                new TargetMethod(
                        "client.frame",
                        List.of("net/minecraft/client/renderer/GameRenderer"),
                        List.of("render"),
                        "(Lnet/minecraft/client/DeltaTracker;Z)V",
                        TargetMethod.HookKind.FRAME
                ),
                new TargetMethod(
                        "combat.reach.pick",
                        List.of("net/minecraft/client/Minecraft"),
                        List.of("pick"),
                        "(F)V",
                        TargetMethod.HookKind.VOID_RETURN
                ),
                new TargetMethod(
                        "render.static-fov",
                        List.of("net/minecraft/client/Camera"),
                        List.of("calculateFov"),
                        "(F)F",
                        TargetMethod.HookKind.FLOAT_RETURN_ARG
                ),
                new TargetMethod(
                        "client.hud",
                        List.of("net/minecraft/client/gui/Gui"),
                        List.of("extractRenderState"),
                        "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
                        TargetMethod.HookKind.HUD
                ),
                new TargetMethod(
                        "render.scoreboard",
                        List.of("net/minecraft/client/gui/Gui"),
                        List.of("displayScoreboardSidebar"),
                        "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/scores/Objective;)V",
                        TargetMethod.HookKind.SCOREBOARD
                ),
                new TargetMethod(
                        "render.present",
                        List.of("com/mojang/blaze3d/pipeline/RenderTarget"),
                        List.of("blitToScreen"),
                        "()V",
                        TargetMethod.HookKind.VOID_RETURN
                ),
                new TargetMethod(
                        "client.world-render",
                        List.of("net/minecraft/client/renderer/LevelRenderer"),
                        List.of("lambda$addMainPass$0"),
                        "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                                + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                                + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;ZLorg/joml/Matrix4fc;)V",
                        TargetMethod.HookKind.WORLD_RENDER
                ),
                new TargetMethod(
                        "input.key",
                        List.of("net/minecraft/client/KeyboardHandler"),
                        List.of("keyPress"),
                        "(JILnet/minecraft/client/input/KeyEvent;)V",
                        TargetMethod.HookKind.KEY
                ),
                new TargetMethod(
                        "input.mouse.button",
                        List.of("net/minecraft/client/MouseHandler"),
                        List.of("onButton"),
                        "(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
                        TargetMethod.HookKind.MOUSE_BUTTON
                ),
                new TargetMethod(
                        "input.mouse.scroll",
                        List.of("net/minecraft/client/MouseHandler"),
                        List.of("onScroll"),
                        "(JDD)V",
                        TargetMethod.HookKind.MOUSE_SCROLL
                ),
                new TargetMethod(
                        "input.mouse.movement",
                        List.of("net/minecraft/client/MouseHandler"),
                        List.of("handleAccumulatedMovement"),
                        "()V",
                        TargetMethod.HookKind.MOUSE_MOVEMENT
                ),
                new TargetMethod(
                        "input.attack",
                        List.of("net/minecraft/client/Minecraft"),
                        List.of("startAttack"),
                        "()Z",
                        TargetMethod.HookKind.ATTACK
                ),
                new TargetMethod(
                        "input.use",
                        List.of("net/minecraft/client/Minecraft"),
                        List.of("startUseItem"),
                        "()V",
                        TargetMethod.HookKind.USE
                ),
                new TargetMethod(
                        "network.send",
                        List.of("net/minecraft/network/Connection"),
                        List.of("send"),
                        "(Lnet/minecraft/network/protocol/Packet;)V",
                        TargetMethod.HookKind.PACKET_SEND
                ),
                new TargetMethod(
                        "network.receive",
                        List.of("net/minecraft/network/Connection"),
                        List.of("genericsFtw"),
                        "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V",
                        TargetMethod.HookKind.PACKET_RECEIVE
                ),
                new TargetMethod(
                        "network.apply",
                        List.of("net/minecraft/network/protocol/PacketUtils"),
                        List.of("ensureRunningOnSameThread"),
                        "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                        TargetMethod.HookKind.PACKET_APPLY
                ),
                new TargetMethod(
                        "world.block.set",
                        List.of("net/minecraft/client/multiplayer/ClientLevel"),
                        List.of("setBlock"),
                        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
                        TargetMethod.HookKind.BLOCK_SET
                ),
                new TargetMethod(
                        "world.block.server-set",
                        List.of("net/minecraft/client/multiplayer/ClientLevel"),
                        List.of("setServerVerifiedBlockState"),
                        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
                        TargetMethod.HookKind.BLOCK_SERVER_SET
                ),
                new TargetMethod(
                        "player.update",
                        List.of("net/minecraft/client/player/LocalPlayer"),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.PLAYER_UPDATE
                ),
                new TargetMethod(
                        "movement.input",
                        List.of("net/minecraft/client/player/LocalPlayer"),
                        List.of("aiStep"),
                        "()V",
                        TargetMethod.HookKind.MOVE_INPUT
                ),
                new TargetMethod(
                        "movement.item-use-speed",
                        List.of("net/minecraft/client/player/LocalPlayer"),
                        List.of("itemUseSpeedMultiplier"),
                        "()F",
                        TargetMethod.HookKind.FLOAT_RETURN
                ),
                new TargetMethod(
                        "player.move",
                        List.of("net/minecraft/world/entity/Entity"),
                        List.of("moveRelative"),
                        "(FLnet/minecraft/world/phys/Vec3;)V",
                        TargetMethod.HookKind.PLAYER_MOVE
                ),
                new TargetMethod(
                        "player.position",
                        List.of("net/minecraft/client/player/LocalPlayer"),
                        List.of("sendPosition"),
                        "()V",
                        TargetMethod.HookKind.PLAYER_POSITION
                ),
                new TargetMethod(
                        "render.avatar-state",
                        List.of("net/minecraft/client/renderer/entity/player/AvatarRenderer"),
                        List.of("extractRenderState"),
                        "(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
                        TargetMethod.HookKind.RENDER_STATE
                ),
                new TargetMethod(
                        "render.player-nametag",
                        List.of("net/minecraft/client/renderer/entity/player/AvatarRenderer"),
                        List.of("submitNameDisplay"),
                        "(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;"
                                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                        TargetMethod.HookKind.BOOLEAN_GATE
                ),
                new TargetMethod(
                        "render.close",
                        List.of("net/minecraft/client/renderer/GameRenderer"),
                        List.of("close"),
                        "()V",
                        TargetMethod.HookKind.RENDERER_CLOSE
                ),
                new TargetMethod(
                        "render.silent-aura-animation",
                        List.of("net/minecraft/client/renderer/ItemInHandRenderer"),
                        List.of("renderArmWithItem"),
                        "(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;F"
                                + "Lnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
                        TargetMethod.HookKind.HAND_ANIMATION
                ),
                new TargetMethod(
                        "render.scaffold-item-spoof",
                        List.of("net/minecraft/client/renderer/ItemInHandRenderer"),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.OBJECT_INVOKE_RETURN
                ),
                new TargetMethod(
                        "render.scaffold-hud-item-spoof",
                        List.of("net/minecraft/client/gui/Gui"),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.OBJECT_INVOKE_RETURN
                ),
                new TargetMethod(
                        "movement.living-ai-step",
                        List.of("net/minecraft/world/entity/LivingEntity"),
                        List.of("aiStep"),
                        "()V",
                        TargetMethod.HookKind.VOID_HEAD
                ),
                new TargetMethod(
                        "movement.keyboard-input",
                        List.of("net/minecraft/client/player/KeyboardInput"),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.VOID_RETURN
                ),
                new TargetMethod(
                        "render.camera-zoom",
                        List.of("net/minecraft/client/Camera"),
                        List.of("getMaxZoom"),
                        "(F)F",
                        TargetMethod.HookKind.FLOAT_HEAD_BOOLEAN_GATE
                ),
                new TargetMethod(
                        "render.frustum-visible",
                        List.of("net/minecraft/client/renderer/culling/Frustum"),
                        List.of("isVisible"),
                        "(Lnet/minecraft/world/phys/AABB;)Z",
                        TargetMethod.HookKind.BOOLEAN_RETURN_ARG
                ),
                new TargetMethod(
                        "render.player-name",
                        List.of("net/minecraft/world/entity/player/Player"),
                        List.of("getName", "getDisplayName"),
                        "()Lnet/minecraft/network/chat/Component;",
                        TargetMethod.HookKind.OBJECT_RETURN
                ),
                new TargetMethod(
                        "render.tab-name",
                        List.of("net/minecraft/client/gui/components/PlayerTabOverlay"),
                        List.of("getNameForDisplay"),
                        "(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
                        TargetMethod.HookKind.OBJECT_RETURN
                ),
                new TargetMethod(
                        "render.chat-system-name",
                        List.of("net/minecraft/client/gui/components/ChatComponent"),
                        List.of("addClientSystemMessage", "addServerSystemMessage"),
                        "(Lnet/minecraft/network/chat/Component;)V",
                        TargetMethod.HookKind.OBJECT_ARGUMENT
                ),
                new TargetMethod(
                        "render.chat-player-name",
                        List.of("net/minecraft/client/gui/components/ChatComponent"),
                        List.of("addPlayerMessage"),
                        "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
                                + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
                        TargetMethod.HookKind.OBJECT_ARGUMENT
                ),
                new TargetMethod(
                        "render.chat-filter.player",
                        List.of("net/minecraft/client/gui/components/ChatComponent"),
                        List.of("addPlayerMessage"),
                        "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
                                + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
                        TargetMethod.HookKind.BOOLEAN_GATE
                ),
                new TargetMethod(
                        "render.chat-filter.server-system",
                        List.of("net/minecraft/client/gui/components/ChatComponent"),
                        List.of("addServerSystemMessage"),
                        "(Lnet/minecraft/network/chat/Component;)V",
                        TargetMethod.HookKind.BOOLEAN_GATE
                ),
                new TargetMethod(
                        "render.full-bright",
                        List.of("net/minecraft/client/renderer/LightmapRenderStateExtractor"),
                        List.of("extract"),
                        "(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V",
                        TargetMethod.HookKind.NAMED_FLOAT_LOCAL
                ),
                new TargetMethod(
                        "movement.sprint-decisions",
                        List.of("net/minecraft/client/player/LocalPlayer"),
                        List.of("aiStep"),
                        "()V",
                        TargetMethod.HookKind.SPRINT_DECISIONS
                ),
                new TargetMethod(
                        "movement.jump-yaw",
                        List.of("net/minecraft/world/entity/LivingEntity"),
                        List.of("jumpFromGround"),
                        "()V",
                        TargetMethod.HookKind.YAW_RESULT
                ),
                new TargetMethod(
                        "combat.block-break-start",
                        List.of("net/minecraft/client/multiplayer/MultiPlayerGameMode"),
                        List.of("startDestroyBlock", "continueDestroyBlock"),
                        "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
                        TargetMethod.HookKind.BOOLEAN_GATE
                ),
                new TargetMethod(
                        "xray.block-tessellate",
                        List.of("net/minecraft/client/renderer/block/ModelBlockRenderer"),
                        List.of("tesselateBlock"),
                        "(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
                                + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                                + "Lnet/minecraft/core/BlockPos;"
                                + "Lnet/minecraft/world/level/block/state/BlockState;"
                                + "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V",
                        TargetMethod.HookKind.XRAY_TESSELLATE
                ),
                new TargetMethod(
                        "xray.force-opaque",
                        List.of("net/minecraft/client/renderer/block/ModelBlockRenderer"),
                        List.of("forceOpaque"),
                        "(ZLnet/minecraft/world/level/block/state/BlockState;)Z",
                        TargetMethod.HookKind.STATIC_BOOLEAN_RETURN_ARG1
                ),
                new TargetMethod(
                        "xray.quad-alpha",
                        List.of("net/minecraft/client/renderer/block/ModelBlockRenderer"),
                        List.of("putQuadWithTint"),
                        "(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
                                + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                                + "Lnet/minecraft/world/level/block/state/BlockState;"
                                + "Lnet/minecraft/core/BlockPos;"
                                + "Lnet/minecraft/client/resources/model/geometry/BakedQuad;)V",
                        TargetMethod.HookKind.XRAY_QUAD
                ),
                new TargetMethod(
                        "xray.material-layer",
                        List.of("net/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo"),
                        List.of("layer"),
                        "()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;",
                        TargetMethod.HookKind.OBJECT_RETURN
                ),
                new TargetMethod(
                        "xray.section-occlusion",
                        List.of("net/minecraft/client/renderer/chunk/VisGraph"),
                        List.of("setOpaque"),
                        "(Lnet/minecraft/core/BlockPos;)V",
                        TargetMethod.HookKind.BOOLEAN_GATE
                ),
                new TargetMethod(
                        "render.clip-occlusion",
                        List.of("net/minecraft/client/renderer/chunk/VisibilitySet"),
                        List.of("visibilityBetween"),
                        "(Lnet/minecraft/core/Direction;Lnet/minecraft/core/Direction;)Z",
                        TargetMethod.HookKind.BOOLEAN_RETURN_ARG
                ),
                new TargetMethod(
                        "combat.player-extra-knockback",
                        List.of("net/minecraft/world/entity/player/Player"),
                        List.of("causeExtraKnockback"),
                        "(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;)V",
                        TargetMethod.HookKind.VOID_START_END_ARG0
                ),
                new TargetMethod(
                        "combat.player-attack-observe",
                        List.of("net/minecraft/world/entity/player/Player"),
                        List.of("attack"),
                        "(Lnet/minecraft/world/entity/Entity;)V",
                        TargetMethod.HookKind.VOID_START_END_ARG0
                ),
                new TargetMethod(
                        "command.client",
                        List.of("net/minecraft/client/multiplayer/ClientPacketListener"),
                        List.of("sendChat"),
                        "(Ljava/lang/String;)V",
                        TargetMethod.HookKind.BOOLEAN_GATE
                ),
                new TargetMethod(
                        "render.chams-frame",
                        List.of("net/minecraft/client/renderer/LevelRenderer"),
                        List.of("lambda$addMainPass$0"),
                        "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                                + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                                + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                                + "Lcom/mojang/blaze3d/resource/ResourceHandle;ZLorg/joml/Matrix4fc;)V",
                        TargetMethod.HookKind.CHAMS_FRAME
                ),
                new TargetMethod(
                        "render.chams-submit",
                        List.of("net/minecraft/client/renderer/entity/LivingEntityRenderer"),
                        List.of("submit"),
                        "(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                        TargetMethod.HookKind.VOID_START_END_ARG0
                ),
                new TargetMethod(
                        "render.armor-hide",
                        List.of("net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer"),
                        List.of("submit"),
                        "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                                + "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
                        TargetMethod.HookKind.BOOLEAN_GATE
                ),
                new TargetMethod(
                        "render.chams-type",
                        List.of("net/minecraft/client/renderer/entity/LivingEntityRenderer"),
                        List.of("getRenderType"),
                        "(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)"
                                + "Lnet/minecraft/client/renderer/rendertype/RenderType;",
                        TargetMethod.HookKind.OBJECT_RETURN
                ),
                new TargetMethod(
                        "render.chams-equipment",
                        List.of("net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer"),
                        List.of("renderLayers"),
                        "(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
                                + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;"
                                + "Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;"
                                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                                + "Lnet/minecraft/resources/Identifier;II)V",
                        TargetMethod.HookKind.CHAMS_REMAP_SUBMIT_MODEL
                ),
                new TargetMethod(
                        "render.trim-changer",
                        List.of("net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer"),
                        List.of("renderLayers"),
                        "(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
                                + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;"
                                + "Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;"
                                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                                + "Lnet/minecraft/resources/Identifier;II)V",
                        TargetMethod.HookKind.ITEM_STACK_ARGUMENT_5
                ),
                new TargetMethod(
                        "render.chams-cape",
                        List.of("net/minecraft/client/renderer/entity/layers/CapeLayer"),
                        List.of("submit"),
                        "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                                + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
                        TargetMethod.HookKind.CHAMS_REMAP_SUBMIT_MODEL
                ),
                new TargetMethod(
                        "render.chams-mark-item",
                        List.of("net/minecraft/client/renderer/SubmitNodeCollection"),
                        List.of("submitItem"),
                        "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/world/item/ItemDisplayContext;III[ILjava/util/List;"
                                + "Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V",
                        TargetMethod.HookKind.CHAMS_MARK_ITEM
                ),
                new TargetMethod(
                        "render.chams-item",
                        List.of("net/minecraft/client/renderer/feature/ItemFeatureRenderer"),
                        List.of("renderItem"),
                        "(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                                + "Lnet/minecraft/client/renderer/OutlineBufferSource;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeStorage$ItemSubmit;)V",
                        TargetMethod.HookKind.CHAMS_ITEM_RENDER
                ),
                new TargetMethod(
                        "render.chams-foil",
                        List.of("net/minecraft/client/renderer/feature/ItemFeatureRenderer"),
                        List.of("getFoilBuffer"),
                        "(Lnet/minecraft/client/renderer/MultiBufferSource;"
                                + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                                + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)"
                                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;",
                        TargetMethod.HookKind.CHAMS_FOIL_BUFFER
                ),
                new TargetMethod(
                        "optional.sodium.render-model",
                        List.of("net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer"),
                        List.of("renderModel"),
                        "(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
                                + "Lnet/minecraft/world/level/block/state/BlockState;"
                                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
                        TargetMethod.HookKind.SODIUM_RENDER_MODEL
                ),
                new TargetMethod(
                        "optional.sodium.process-quad",
                        List.of("net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer"),
                        List.of("processQuad"),
                        "(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;)V",
                        TargetMethod.HookKind.SODIUM_PROCESS_QUAD
                ),
                new TargetMethod(
                        "optional.sodium.world-render",
                        List.of("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer"),
                        List.of("drawChunkLayer"),
                        "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                                + "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                                + "DDDLcom/mojang/blaze3d/textures/GpuSampler;)V",
                        TargetMethod.HookKind.VOID_START_END_ARG0
                ),
                new TargetMethod(
                        "optional.sodium.clip-occlusion",
                        List.of("net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager"),
                        List.of("shouldUseOcclusionCulling"),
                        "(Lnet/minecraft/client/Camera;Z)Z",
                        TargetMethod.HookKind.BOOLEAN_RETURN_ARG
                )
        ));
    }

}
