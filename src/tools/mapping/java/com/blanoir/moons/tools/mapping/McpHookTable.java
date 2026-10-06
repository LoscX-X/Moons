package com.blanoir.moons.tools.mapping;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Generates precise named/obfuscated targets from the verified game and MCP inputs. */
public final class McpHookTable {
    private record Hook(String id, String owner, String name, String descriptor, String kind) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Map<String, String> names = new HashMap<>(),
                classes = new HashMap<>(),
                members = new HashMap<>();
        for (String line : Files.readAllLines(root.resolve("methods.csv"))) {
            String[] p = line.split(",", 3);
            if (p.length > 1) names.put(p[0], p[1]);
        }
        Map<String, String> fieldNames = new HashMap<>();
        for (String line : Files.readAllLines(root.resolve("fields.csv"))) {
            String[] p = line.split(",", 3);
            if (p.length > 1) fieldNames.put(p[0], p[1]);
        }
        List<String> reflection = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("joined.srg"))) {
            String[] p = line.split(" ");
            if (p[0].equals("CL:")) classes.put(p[2], p[1]);
            if (p[0].equals("MD:")) {
                int split = p[3].lastIndexOf('/');
                String named =
                        names.getOrDefault(p[3].substring(split + 1), p[3].substring(split + 1));
                members.put(
                        p[3].substring(0, split + 1) + named + p[4],
                        p[1].substring(p[1].lastIndexOf('/') + 1));
                reflection.add(
                        p[3].substring(0, split).replace('/', '.')
                                + "#"
                                + named
                                + "="
                                + p[1].substring(p[1].lastIndexOf('/') + 1));
            }
            if (p[0].equals("FD:")) {
                int split = p[2].lastIndexOf('/');
                String named =
                        fieldNames.getOrDefault(
                                p[2].substring(split + 1), p[2].substring(split + 1));
                reflection.add(
                        p[2].substring(0, split).replace('/', '.')
                                + "#"
                                + named
                                + "="
                                + p[1].substring(p[1].lastIndexOf('/') + 1));
            }
        }
        Remapper remapper =
                new Remapper(Opcodes.ASM9) {
                    @Override
                    public String map(String n) {
                        return classes.getOrDefault(n, n);
                    }
                };
        List<Hook> hooks = new ArrayList<>();
        String mc = "net/minecraft/client/Minecraft",
                renderer = "net/minecraft/client/renderer/EntityRenderer",
                gui = "net/minecraft/client/gui/GuiIngame",
                player = "net/minecraft/client/entity/EntityPlayerSP";
        hooks.add(new Hook("client.tick", mc, "runTick", "()V", "TICK"));
        hooks.add(new Hook("client.frame", renderer, "updateCameraAndRender", "(FJ)V", "FRAME"));
        hooks.add(new Hook("client.hud", gui, "renderGameOverlay", "(F)V", "HUD"));
        hooks.add(new Hook("client.world-render", renderer, "renderWorldPass", "(IFJ)V", "WORLD"));
        hooks.add(new Hook("client.attack", mc, "clickMouse", "()V", "ATTACK"));
        hooks.add(new Hook("client.use", mc, "rightClickMouse", "()V", "USE"));

        hooks.add(new Hook("player.living-tick", player, "onLivingUpdate", "()V", "VOID_HEAD"));
        hooks.add(new Hook("player.position", player, "onUpdateWalkingPlayer", "()V", "POSITION"));
        hooks.add(
                new Hook("player.update", player, "onUpdateWalkingPlayer", "()V", "PLAYER_UPDATE"));
        hooks.add(
                new Hook(
                        "movement.keyboard-input",
                        "net/minecraft/util/MovementInputFromOptions",
                        "updatePlayerMoveState",
                        "()V",
                        "EXIT"));
        hooks.add(new Hook("combat.reach.pick", renderer, "getMouseOver", "(F)V", "VOID_RETURN"));
        hooks.add(
                new Hook("render.static-fov", renderer, "getFOVModifier", "(FZ)F", "FLOAT_RETURN"));
        hooks.add(new Hook("render.camera", renderer, "orientCamera", "(F)V", "SCOPE"));
        hooks.add(
                new Hook(
                        "render.camera-ray",
                        "net/minecraft/world/World",
                        "rayTraceBlocks",
                        "(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;ZZZ)Lnet/minecraft/util/MovingObjectPosition;",
                        "GATE"));
        hooks.add(new Hook("render.effects", renderer, "setupFog", "(IF)V", "SCOPE"));
        hooks.add(new Hook("render.effects", renderer, "updateFogColor", "(F)V", "SCOPE"));
        hooks.add(new Hook("render.effects", renderer, "setupCameraTransform", "(FI)V", "SCOPE"));
        hooks.add(new Hook("render.nausea", renderer, "setupCameraTransform", "(FI)V", "SCOPE"));
        hooks.add(
                new Hook(
                        "render.scoreboard",
                        gui,
                        "renderScoreboard",
                        "(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.hand",
                        "net/minecraft/client/renderer/ItemRenderer",
                        "renderItemInFirstPerson",
                        "(F)V",
                        "VOID_HEAD"));
        hooks.add(new Hook("render.present", mc, "updateDisplay", "()V", "ENTER"));
        hooks.add(
                new Hook(
                        "render.lightmap",
                        "net/minecraft/client/renderer/EntityRenderer",
                        "updateLightmap",
                        "(F)V",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "client.input",
                        "net/minecraft/client/Minecraft",
                        "runTick",
                        "()V",
                        "INPUT_LOOPS"));
        hooks.add(
                new Hook(
                        "screen.input",
                        "net/minecraft/client/gui/GuiScreen",
                        "handleInput",
                        "()V",
                        "INPUT_LOOPS"));
        hooks.add(
                new Hook(
                        "client.timer", "net/minecraft/util/Timer", "updateTimer", "()V", "ENTER"));
        hooks.add(
                new Hook(
                        "render.freelook.turn",
                        "net/minecraft/entity/Entity",
                        "setAngles",
                        "(FF)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "input.mouse-motion",
                        "net/minecraft/entity/Entity",
                        "setAngles",
                        "(FF)V",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "movement.relative",
                        "net/minecraft/entity/Entity",
                        "moveFlying",
                        "(FFF)V",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "movement.jump",
                        "net/minecraft/entity/EntityLivingBase",
                        "jump",
                        "()V",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "movement.sprint",
                        "net/minecraft/entity/EntityLivingBase",
                        "setSprinting",
                        "(Z)V",
                        "ARG:0"));
        hooks.add(
                new Hook(
                        "combat.attack",
                        "net/minecraft/entity/player/EntityPlayer",
                        "attackTargetEntityWithCurrentItem",
                        "(Lnet/minecraft/entity/Entity;)V",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "combat.block-break-start",
                        "net/minecraft/client/multiplayer/PlayerControllerMP",
                        "clickBlock",
                        "(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z",
                        "GATE"));
        hooks.add(
                new Hook(
                        "combat.block-break-continue",
                        "net/minecraft/client/multiplayer/PlayerControllerMP",
                        "onPlayerDamageBlock",
                        "(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z",
                        "GATE"));
        hooks.add(
                new Hook(
                        "world.block-update",
                        "net/minecraft/client/multiplayer/WorldClient",
                        "invalidateRegionAndSetBlock",
                        "(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;)Z",
                        "ENTER"));
        hooks.add(
                new Hook(
                        "world.block-update.result",
                        "net/minecraft/client/multiplayer/WorldClient",
                        "invalidateRegionAndSetBlock",
                        "(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;)Z",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "render.entity",
                        "net/minecraft/client/renderer/entity/RenderManager",
                        "doRenderEntity",
                        "(Lnet/minecraft/entity/Entity;DDDFFZ)Z",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.ysm-sub-entity",
                        "net/minecraft/client/renderer/entity/RenderManager",
                        "doRenderEntity",
                        "(Lnet/minecraft/entity/Entity;DDDFFZ)Z",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.entity-position",
                        "net/minecraft/client/renderer/entity/RenderManager",
                        "doRenderEntity",
                        "(Lnet/minecraft/entity/Entity;DDDFFZ)Z",
                        "ARGS"));
        hooks.add(
                new Hook(
                        "render.living",
                        "net/minecraft/client/renderer/entity/RendererLivingEntity",
                        "doRender",
                        "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "render.player-nametag",
                        "net/minecraft/client/renderer/entity/RendererLivingEntity",
                        "canRenderName",
                        "(Lnet/minecraft/entity/EntityLivingBase;)Z",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "render.shadow-fire",
                        "net/minecraft/client/renderer/entity/Render",
                        "doRenderShadowAndFire",
                        "(Lnet/minecraft/entity/Entity;DDDFF)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.armor-hide",
                        "net/minecraft/client/renderer/entity/layers/LayerArmorBase",
                        "doRenderLayer",
                        "(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.armor-hide.head",
                        "net/minecraft/client/renderer/entity/layers/LayerCustomHead",
                        "doRenderLayer",
                        "(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.animation",
                        "net/minecraft/client/renderer/ItemRenderer",
                        "transformFirstPersonItem",
                        "(FF)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.animation.block",
                        "net/minecraft/client/renderer/ItemRenderer",
                        "doBlockTransformations",
                        "()V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.animation.render",
                        "net/minecraft/client/renderer/ItemRenderer",
                        "renderItemInFirstPerson",
                        "(F)V",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "render.scaffold-item-spoof",
                        "net/minecraft/client/renderer/ItemRenderer",
                        "updateEquippedItem",
                        "()V",
                        "HELD_ITEM"));
        hooks.add(
                new Hook("render.scaffold-hud-item-spoof", gui, "updateTick", "()V", "HELD_ITEM"));
        hooks.add(
                new Hook(
                        "render.animation.third-person",
                        "net/minecraft/client/model/ModelBiped",
                        "setRotationAngles",
                        "(FFFFFFLnet/minecraft/entity/Entity;)V",
                        "ENTER"));
        hooks.add(
                new Hook(
                        "render.ysm-player",
                        "net/minecraft/client/renderer/entity/RenderPlayer",
                        "doRender",
                        "(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.ysm-right-hand",
                        "net/minecraft/client/renderer/entity/RenderPlayer",
                        "renderRightArm",
                        "(Lnet/minecraft/client/entity/AbstractClientPlayer;)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.ysm-left-hand",
                        "net/minecraft/client/renderer/entity/RenderPlayer",
                        "renderLeftArm",
                        "(Lnet/minecraft/client/entity/AbstractClientPlayer;)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "render.player-name",
                        "net/minecraft/entity/player/EntityPlayer",
                        "getDisplayName",
                        "()Lnet/minecraft/util/IChatComponent;",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "render.tab-name",
                        "net/minecraft/client/gui/GuiPlayerTabOverlay",
                        "getPlayerName",
                        "(Lnet/minecraft/client/network/NetworkPlayerInfo;)Ljava/lang/String;",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "render.player-skin",
                        "net/minecraft/client/network/NetworkPlayerInfo",
                        "getLocationSkin",
                        "()Lnet/minecraft/util/ResourceLocation;",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "render.chat-name",
                        "net/minecraft/client/gui/GuiNewChat",
                        "printChatMessageWithOptionalDeletion",
                        "(Lnet/minecraft/util/IChatComponent;I)V",
                        "ARG:0"));
        hooks.add(
                new Hook(
                        "chat.filter",
                        "net/minecraft/client/network/NetHandlerPlayClient",
                        "handleChat",
                        "(Lnet/minecraft/network/play/server/S02PacketChat;)V",
                        "GATE_APPLY"));
        hooks.add(
                new Hook(
                        "command.client",
                        "net/minecraft/client/entity/EntityPlayerSP",
                        "sendChatMessage",
                        "(Ljava/lang/String;)V",
                        "GATE"));
        hooks.add(
                new Hook(
                        "placement.item-ray",
                        "net/minecraft/item/Item",
                        "getMovingObjectPositionFromPlayer",
                        "(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;Z)Lnet/minecraft/util/MovingObjectPosition;",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "render.frustum-visible",
                        "net/minecraft/client/renderer/culling/Frustum",
                        "isBoundingBoxInFrustum",
                        "(Lnet/minecraft/util/AxisAlignedBB;)Z",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "render.clip-occlusion",
                        "net/minecraft/client/renderer/chunk/VisGraph",
                        "computeVisibility",
                        "()Lnet/minecraft/client/renderer/chunk/SetVisibility;",
                        "RETURN"));
        hooks.add(
                new Hook(
                        "xray.block-scope",
                        "net/minecraft/client/renderer/BlockRendererDispatcher",
                        "renderBlock",
                        "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                        "SCOPE"));
        hooks.add(
                new Hook(
                        "xray.block",
                        "net/minecraft/client/renderer/BlockRendererDispatcher",
                        "renderBlock",
                        "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                        "GATE"));
        hooks.add(
                new Hook(
                        "xray.vertex-data",
                        "net/minecraft/client/renderer/WorldRenderer",
                        "addVertexData",
                        "([I)V",
                        "ARG:0"));
        hooks.add(
                new Hook(
                        "xray.vertex-alpha",
                        "net/minecraft/client/renderer/WorldRenderer",
                        "color",
                        "(IIII)Lnet/minecraft/client/renderer/WorldRenderer;",
                        "ARG:3"));
        hooks.add(
                new Hook(
                        "render.close",
                        "net/minecraft/client/Minecraft",
                        "shutdownMinecraftApplet",
                        "()V",
                        "ENTER"));
        String network = "net/minecraft/network/NetworkManager";
        hooks.add(
                new Hook(
                        "packet.send",
                        network,
                        "sendPacket",
                        "(Lnet/minecraft/network/Packet;)V",
                        "PACKET_SEND"));
        hooks.add(
                new Hook(
                        "packet.send-listeners",
                        network,
                        "sendPacket",
                        "(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V",
                        "PACKET_SEND"));
        hooks.add(
                new Hook(
                        "packet.receive",
                        network,
                        "channelRead0",
                        "(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V",
                        "PACKET_RECEIVE"));
        try (JarFile jar = new JarFile(root.resolve("minecraft-1.8.9-named.jar").toFile())) {
            String owner = "net/minecraft/client/network/NetHandlerPlayClient";
            byte[] bytes;
            try (var in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
                bytes = in.readAllBytes();
            }
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int a, String n, String d, String s, String[] e) {
                                    if ((a & (Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE)) != 0
                                            || !n.startsWith("handle")
                                            || !d.startsWith("(Lnet/minecraft/network/play/server/")
                                            || Type.getArgumentTypes(d).length != 1
                                            || Type.getReturnType(d).getSort() != Type.VOID)
                                        return null;
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        private boolean handoff;

                                        @Override
                                        public void visitMethodInsn(
                                                int opcode,
                                                String target,
                                                String name,
                                                String desc,
                                                boolean itf) {
                                            if (opcode == Opcodes.INVOKESTATIC
                                                    && target.equals(
                                                            "net/minecraft/network/PacketThreadUtil"))
                                                handoff = true;
                                        }

                                        @Override
                                        public void visitEnd() {
                                            if (handoff)
                                                hooks.add(
                                                        new Hook(
                                                                "packet.apply." + n,
                                                                owner,
                                                                n,
                                                                d,
                                                                "PACKET_APPLY"));
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                String target = entry.getName().substring(0, entry.getName().length() - 6);
                if (!target.startsWith("net/minecraft/block/")
                        && !target.equals("net/minecraft/entity/EntityLivingBase")) continue;
                try (var in = jar.getInputStream(entry)) {
                    new ClassReader(in)
                            .accept(
                                    new ClassVisitor(Opcodes.ASM9) {
                                        @Override
                                        public MethodVisitor visitMethod(
                                                int a, String n, String d, String s, String[] e) {
                                            if (n.equals("getBlockLayer")
                                                    && d.equals(
                                                            "()Lnet/minecraft/util/EnumWorldBlockLayer;"))
                                                hooks.add(
                                                        new Hook(
                                                                "xray.layer",
                                                                target,
                                                                n,
                                                                d,
                                                                "RETURN"));
                                            if (n.equals("shouldSideBeRendered")
                                                    && d.equals(
                                                            "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z"))
                                                hooks.add(
                                                        new Hook(
                                                                "xray.side",
                                                                target,
                                                                n,
                                                                d,
                                                                "RETURN"));
                                            if (n.equals("isPotionActive")
                                                    && (d.equals("(I)Z")
                                                            || d.equals(
                                                                    "(Lnet/minecraft/potion/Potion;)Z")))
                                                hooks.add(
                                                        new Hook(
                                                                "render.antidebuff",
                                                                target,
                                                                n,
                                                                d,
                                                                "RETURN"));
                                            return null;
                                        }
                                    },
                                    ClassReader.SKIP_CODE);
                }
            }
            // Validate every named method before generating obfuscated targets.
            for (Hook hook : hooks) {
                Set<String> methods = new HashSet<>();
                try (var in = jar.getInputStream(jar.getJarEntry(hook.owner + ".class"))) {
                    new ClassReader(in)
                            .accept(
                                    new ClassVisitor(Opcodes.ASM9) {
                                        @Override
                                        public MethodVisitor visitMethod(
                                                int a, String n, String d, String s, String[] e) {
                                            methods.add(n + d);
                                            return null;
                                        }
                                    },
                                    ClassReader.SKIP_CODE);
                }
                if (!methods.contains(hook.name + hook.descriptor))
                    throw new IllegalStateException("Missing target " + hook);
            }
        }
        List<String> output = new ArrayList<>();
        for (Hook h : hooks) {
            String obfName = members.get(h.owner + "/" + h.name + h.descriptor);
            if (obfName == null) throw new IllegalStateException("Missing MCP mapping " + h);
            output.add(
                    String.join(
                            "\t",
                            h.id,
                            h.owner,
                            h.name,
                            h.descriptor,
                            remapper.map(h.owner),
                            obfName,
                            remapper.mapMethodDesc(h.descriptor),
                            h.kind));
        }
        Path target = Path.of(args[1]);
        Files.createDirectories(target.getParent());
        Files.write(target, output);
        Files.write(target.resolveSibling("members.properties"), reflection);
        System.out.println("1.8.9 exact hooks: " + hooks.size());
    }
}
