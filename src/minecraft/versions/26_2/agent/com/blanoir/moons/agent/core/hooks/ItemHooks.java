package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Minecraft 26.2 item submission and material preparation hooks. */
public final class ItemHooks {
    private ItemHooks() {}

    public static boolean markItem(MethodNode method, String id) {
        boolean sawSubmit = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.desc.equals(
                            "net/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit")) {
                sawSubmit = true;
                continue;
            }
            if (sawSubmit
                    && instruction instanceof VarInsnNode variable
                    && instruction.getOpcode() == Opcodes.ASTORE) {
                InsnList hook = new InsnList();
                hook.add(new LdcInsnNode(id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ALOAD, variable.var));
                hook.add(
                        call(
                                "onVoidHook",
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
                method.instructions.insert(instruction, hook);
                return true;
            }
        }
        return false;
    }

    public static boolean prepareItem(MethodNode method, String id) {
        boolean remapped =
                ValueHooks.loadInvocationArgumentRemap(method, id + ".type", "getVertexBuilder", 0);
        remapped |=
                ValueHooks.loadInvocationArgumentRemap(method, id + ".type", "getFoilBuffer", 0);
        if (!remapped) return false;
        ValueHooks.loadVoidStartEndArgument(method, id);
        return true;
    }

    public static boolean foilBuffer(MethodNode method, String id) {
        return ValueHooks.loadInvocationArgumentRemap(method, id + ".type", "getVertexBuilder", 0);
    }
}
