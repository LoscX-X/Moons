package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Packet delivery and client-world block update hook placement. */
final class WorldHooks {
    private WorldHooks() {}

    static boolean loadPacketSend(MethodNode method) {
        // Instrument original returns only: the PRE cancellation must not
        // report a packet as sent to rotation and position observers.
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList end = new InsnList();
                    end.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    end.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    end.add(call("onPacketSent", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return end;
                });
        InsnList start = new InsnList();
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 1));
        start.add(call("onPacketSend", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        appendVoidCancellation(start);
        method.instructions.insert(start);
        return true;
    }

    static boolean loadPacketReceive(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(call("onPacketReceive", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    static boolean loadPacketApply(MethodNode method) {
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(call("onPacketApply", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return hook;
                });
        return true;
    }

    static boolean loadBlockSet(MethodNode method) {
        method.instructions.insert(blockUpdateStart());
        int resultLocal = method.maxLocals++;
        beforeReturns(
                method,
                Opcodes.IRETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new InsnNode(Opcodes.DUP));
                    hook.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
                    hook.add(
                            call(
                                    "onBlockUpdate",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)V"));
                    return hook;
                });
        return true;
    }

    static boolean loadBlockServerSet(MethodNode method) {
        method.instructions.insert(blockUpdateStart());
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new InsnNode(Opcodes.ICONST_1));
                    hook.add(
                            call(
                                    "onBlockUpdate",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)V"));
                    return hook;
                });
        return true;
    }

    private static InsnList blockUpdateStart() {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(
                call(
                        "onBlockUpdateStart",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
        return hook;
    }
}
