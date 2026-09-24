package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Version-owned terrain output hooks; alpha and layer changes stay at quad submission. */
public final class TerrainHooks {
    private TerrainHooks() {}

    public static boolean sectionQuad(MethodNode method, String id) {
        MethodInsnNode layerCall = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(
                            "net/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo")
                    && invocation.name.equals("layer")
                    && invocation.desc.equals(
                            "()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;")) {
                layerCall = invocation;
                break;
            }
        }
        if (layerCall == null) return false;

        InsnList alphaHook = new InsnList();
        alphaHook.add(new LdcInsnNode(id + ".alpha"));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 7));
        alphaHook.add(
                call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(alphaHook);

        int layerLocal = method.maxLocals++;
        InsnList layerHook = new InsnList();
        layerHook.add(new VarInsnNode(Opcodes.ASTORE, layerLocal));
        layerHook.add(new LdcInsnNode(id + ".layer"));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 6));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, layerLocal));
        layerHook.add(
                call(
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        layerHook.add(
                new TypeInsnNode(
                        Opcodes.CHECKCAST,
                        "net/minecraft/client/renderer/chunk/ChunkSectionLayer"));
        method.instructions.insert(layerCall, layerHook);
        return true;
    }
}
