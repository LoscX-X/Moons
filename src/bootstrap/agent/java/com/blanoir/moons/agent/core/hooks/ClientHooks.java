package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.loadThisAndCall;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.thisCall;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Client lifecycle, keyboard, mouse and interaction hook placement. */
final class ClientHooks {
    private ClientHooks() {}

    static boolean loadClientTick(MethodNode method) {
        InsnList start = new InsnList();
        loadThisAndCall(start, "onClientTickStart");
        method.instructions.insert(start);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall("onClientTickEnd"));
        return true;
    }

    static boolean loadFrame(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(call("onFrame", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        method.instructions.insert(hook);
        return true;
    }

    static boolean loadKey(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.LLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 3));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 4));
        hook.add(call("onKey", "(Ljava/lang/Object;JILjava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    static boolean loadMouseButton(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.LLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 4));
        hook.add(call("onMouse", "(Ljava/lang/Object;JLjava/lang/Object;I)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    static boolean loadMouseScroll(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.LLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 3));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 5));
        hook.add(call("onMouseScroll", "(Ljava/lang/Object;JDD)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    static boolean loadBooleanAction(MethodNode method, String hookName, String endHookName) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(call(hookName, "(Ljava/lang/Object;)Z"));
        LabelNode proceed = new LabelNode();
        hook.add(new JumpInsnNode(Opcodes.IFNE, proceed));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new InsnNode(Opcodes.IRETURN));
        hook.add(proceed);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(hook);
        beforeReturns(method, Opcodes.IRETURN, () -> thisCall(endHookName));
        return true;
    }

    static boolean loadVoidAction(MethodNode method, String hookName, String endHookName) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(call(hookName, "(Ljava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall(endHookName));
        return true;
    }
}
