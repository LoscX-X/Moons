package com.blanoir.moons.loader.common.asm.hooks;

import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.call;
import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.guardHook;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Vanilla terrain tessellation and quad hooks. */
final class XrayHooks {
    private XrayHooks() {}

    static boolean loadXrayTessellate(MethodNode method, String id) {
        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ICONST_3));
        hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        addArrayValue(hook, 0, 5);
        addArrayValue(hook, 1, 6);
        addArrayValue(hook, 2, 7);
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(guardHook(id, hook));
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

    private static void addArrayValue(InsnList instructions, int arrayIndex, int localIndex) {
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new InsnNode(Opcodes.ICONST_0 + arrayIndex));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, localIndex));
        instructions.add(new InsnNode(Opcodes.AASTORE));
    }

    static boolean loadXrayQuad(MethodNode method, String id) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(
                            "net/minecraft/client/renderer/block/BlockQuadOutput")
                    && invocation.name.equals("put")) {
                InsnList hook = new InsnList();
                hook.add(new LdcInsnNode(id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new InsnNode(Opcodes.ACONST_NULL));
                hook.add(
                        call(
                                "onVoidHook",
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                method.instructions.insertBefore(instruction, hook);
                return true;
            }
        }
        return false;
    }
}
