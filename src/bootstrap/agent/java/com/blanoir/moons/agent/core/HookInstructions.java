package com.blanoir.moons.agent.core;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.function.Supplier;

/** Builds bridge instructions; target selection and hook placement remain in the transformer. */
final class HookInstructions {
    static final String BRIDGE = "com/blanoir/moons/api/bridge/AgentBridge";

    private HookInstructions() {}

    /** Both branches preserve the incoming operand stack; callers recompute frames. */
    static InsnList guardHook(String id, InsnList hook) {
        LabelNode skipped = new LabelNode();
        InsnList guarded = new InsnList();
        guarded.add(new LdcInsnNode(id));
        guarded.add(call("isHookActive", "(Ljava/lang/String;)Z"));
        guarded.add(new JumpInsnNode(Opcodes.IFEQ, skipped));
        guarded.add(hook);
        guarded.add(skipped);
        return guarded;
    }

    static void appendVoidCancellation(InsnList hook) {
        LabelNode proceed = new LabelNode();
        hook.add(new JumpInsnNode(Opcodes.IFNE, proceed));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(proceed);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    }

    static InsnList thisCall(String hookName) {
        InsnList result = new InsnList();
        loadThisAndCall(result, hookName);
        return result;
    }

    static void loadThisAndCall(InsnList instructions, String hookName) {
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(call(hookName, "(Ljava/lang/Object;)V"));
    }

    static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, name, descriptor, false);
    }

    static void beforeReturns(MethodNode method, int opcode, Supplier<InsnList> factory) {
        // An instruction node can belong to only one list: create fresh nodes at each return.
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == opcode) {
                method.instructions.insertBefore(instruction, factory.get());
            }
        }
    }

    static boolean containsIdentifiedHook(MethodNode method, String id, String hookName) {
        boolean foundId = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && id.equals(constant.cst)) {
                foundId = true;
            } else if (foundId
                    && instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(BRIDGE)
                    && call.name.equals(hookName)) {
                return true;
            }
        }
        return false;
    }
}
