package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Chams render phases, item submissions and material remapping. */
final class ChamsHooks {
    private ChamsHooks() {}

    static boolean loadChamsFrame(MethodNode method, String id) {
        MethodInsnNode beginPoint = null;
        MethodInsnNode oldEndPoint = null;
        MethodInsnNode secondRenderGroup = null;
        int renderGroupOrdinal = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)) continue;
            if (invocation.name.equals("renderSolidFeatures")) beginPoint = invocation;
            if (invocation.name.equals("renderTranslucentParticles")) oldEndPoint = invocation;
            if (invocation.owner.equals(
                            "net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame")
                    && invocation.name.equals("executeSolid")) {
                beginPoint = invocation;
            }
            if (invocation.owner.equals(
                            "net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame")
                    && invocation.name.equals("executeTranslucentAfterTerrain")) {
                oldEndPoint = invocation;
            }
            if (invocation.owner.equals("net/minecraft/client/renderer/chunk/ChunkSectionsToRender")
                    && invocation.name.equals("renderGroup")
                    && renderGroupOrdinal++ == 1) {
                secondRenderGroup = invocation;
            }
        }
        MethodInsnNode endPoint = oldEndPoint != null ? oldEndPoint : secondRenderGroup;
        if (beginPoint == null || endPoint == null) return false;
        method.instructions.insertBefore(beginPoint, methodHook(id + ".begin"));
        method.instructions.insert(endPoint, methodHook(id + ".end"));
        return true;
    }

    private static InsnList methodHook(String id) {
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ACONST_NULL));
        hook.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        return hook;
    }

    static boolean loadChamsItemRender(MethodNode method, String id) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(id));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 3));
        start.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(start);
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new LdcInsnNode(id + ".end"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    end.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        return ValueHooks.loadInvocationArgumentRemap(method, id + ".type", "getBuffer", 0);
    }
}
