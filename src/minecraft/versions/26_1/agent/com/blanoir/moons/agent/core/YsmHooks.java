package com.blanoir.moons.agent.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Observe the completed state without retaining game types in the bootstrap API. */
final class YsmHooks {
    static boolean audioStream(MethodNode method, String id) {
        var hook = new InsnList();
        var fallback = new LabelNode();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new InsnNode(Opcodes.ICONST_2));
        hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new InsnNode(Opcodes.AASTORE));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 2));
        hook.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Boolean",
                        "valueOf",
                        "(Z)Ljava/lang/Boolean;",
                        false));
        hook.add(new InsnNode(Opcodes.AASTORE));
        hook.add(new InsnNode(Opcodes.ACONST_NULL));
        hook.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/blanoir/moons/api/bridge/AgentBridge",
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        false));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/concurrent/CompletableFuture"));
        hook.add(new InsnNode(Opcodes.ARETURN));
        hook.add(fallback);
        hook.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(hook);
        return true;
    }

    static boolean capture(MethodNode method, String id) {
        int result = method.maxLocals++;
        boolean found = false;
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            var hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ASTORE, result));
            hook.add(new LdcInsnNode(id));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new InsnNode(Opcodes.ICONST_2));
            hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new InsnNode(Opcodes.ICONST_0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, result));
            hook.add(new InsnNode(Opcodes.AASTORE));
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new InsnNode(Opcodes.ICONST_1));
            hook.add(new VarInsnNode(Opcodes.FLOAD, 2));
            hook.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Float",
                            "valueOf",
                            "(F)Ljava/lang/Float;",
                            false));
            hook.add(new InsnNode(Opcodes.AASTORE));
            hook.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "com/blanoir/moons/api/bridge/AgentBridge",
                            "onVoidHook",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
                            false));
            hook.add(new VarInsnNode(Opcodes.ALOAD, result));
            method.instructions.insertBefore(instruction, hook);
            found = true;
        }
        return found;
    }
}
