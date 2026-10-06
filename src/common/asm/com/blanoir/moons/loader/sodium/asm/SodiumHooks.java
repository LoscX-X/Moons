package com.blanoir.moons.loader.sodium.asm;

import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.call;
import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.guardHook;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Sodium-specific terrain hooks; ordinary hook instructions are shared. */
public final class SodiumHooks {
    private SodiumHooks() {}

    public static boolean loadSodiumRenderModel(MethodNode method, String id) {
        InsnList start = new InsnList();
        start.add(new LdcInsnNode(id));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new InsnNode(Opcodes.ICONST_3));
        start.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_0));
        start.add(new InsnNode(Opcodes.ACONST_NULL));
        start.add(new InsnNode(Opcodes.AASTORE));
        addArrayValue(start, 1, 3);
        addArrayValue(start, 2, 2);
        start.add(new InsnNode(Opcodes.ICONST_1));
        start.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(start);
        method.instructions.insert(guardHook(id, start));
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new LdcInsnNode(id + ".end"));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new InsnNode(Opcodes.ACONST_NULL));
                    end.add(
                            call(
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        return true;
    }

    public static boolean loadSodiumProcessQuad(MethodNode method, String id) {
        FieldInsnNode forceOpaque = null;
        MethodInsnNode renderType = null;
        MethodInsnNode shadeQuad = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("forceOpaque")
                    && field.desc.equals("Z")) {
                forceOpaque = field;
            } else if (instruction instanceof MethodInsnNode invocation
                    && invocation.name.equals("getRenderType")
                    && invocation.desc.equals(
                            "()Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;")) {
                renderType = invocation;
            } else if (instruction instanceof MethodInsnNode invocation
                    && invocation.name.equals("shadeQuad")) {
                shadeQuad = invocation;
            }
        }
        if (forceOpaque == null || renderType == null || shadeQuad == null) return false;

        int booleanLocal = method.maxLocals++;
        InsnList opaqueHook = new InsnList();
        opaqueHook.add(new VarInsnNode(Opcodes.ISTORE, booleanLocal));
        opaqueHook.add(new LdcInsnNode(id + ".force-opaque"));
        opaqueHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        opaqueHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        opaqueHook.add(new VarInsnNode(Opcodes.ILOAD, booleanLocal));
        opaqueHook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        method.instructions.insert(forceOpaque, opaqueHook);

        int objectLocal = method.maxLocals++;
        InsnList layerHook = new InsnList();
        layerHook.add(new VarInsnNode(Opcodes.ASTORE, objectLocal));
        layerHook.add(new LdcInsnNode(id + ".layer"));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        layerHook.add(new VarInsnNode(Opcodes.ALOAD, objectLocal));
        layerHook.add(
                call(
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        layerHook.add(
                new TypeInsnNode(
                        Opcodes.CHECKCAST,
                        "net/minecraft/client/renderer/chunk/ChunkSectionLayer"));
        method.instructions.insert(renderType, layerHook);

        InsnList alphaHook = new InsnList();
        alphaHook.add(new LdcInsnNode(id + ".alpha"));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        alphaHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        alphaHook.add(
                call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(shadeQuad, alphaHook);
        return true;
    }

    private static void addArrayValue(InsnList instructions, int arrayIndex, int localIndex) {
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new InsnNode(Opcodes.ICONST_0 + arrayIndex));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, localIndex));
        instructions.add(new InsnNode(Opcodes.AASTORE));
    }
}
