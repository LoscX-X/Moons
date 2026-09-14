package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.thisCall;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Player simulation, movement decisions and first-person animation hooks. */
final class PlayerHooks {
    private PlayerHooks() {}

    static boolean loadMoveInput(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.name.equals("tick")
                    || !invocation.desc.equals("()V")
                    || !invocation.owner.equals("net/minecraft/client/player/ClientInput"))
                continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(call("onMoveInput", "(Ljava/lang/Object;)V"));
            method.instructions.insert(invocation, hook);
            return true;
        }
        return false;
    }

    static boolean loadPlayerUpdate(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(call("onPlayerUpdate", "(Ljava/lang/Object;)Z"));
        appendVoidCancellation(hook);
        method.instructions.insert(hook);
        return true;
    }

    static boolean loadPlayerMove(MethodNode method) {
        int result = method.maxLocals++;
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.FLOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(
                call("onPlayerMove", "(Ljava/lang/Object;FLjava/lang/Object;)[Ljava/lang/Object;"));
        hook.add(new VarInsnNode(Opcodes.ASTORE, result));
        hook.add(new VarInsnNode(Opcodes.ALOAD, result));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new InsnNode(Opcodes.AALOAD));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
        hook.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
        hook.add(new VarInsnNode(Opcodes.FSTORE, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, result));
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(new InsnNode(Opcodes.AALOAD));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/phys/Vec3"));
        hook.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.insert(hook);
        beforeReturns(method, Opcodes.RETURN, () -> thisCall("onPlayerMoveEnd"));
        return true;
    }

    static boolean loadSprintDecisions(MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.desc.equals("()Z")) continue;
            String id =
                    switch (invocation.name) {
                        case "canStartSprinting" -> "movement.sprint-start";
                        case "sprint" -> "movement.sprint-input";
                        case "shouldStopRunSprinting" -> "movement.sprint-stop";
                        default -> null;
                    };
            if (id == null) continue;
            InsnList hook = new InsnList();
            int result = method.maxLocals++;
            hook.add(new VarInsnNode(Opcodes.ISTORE, result));
            hook.add(new LdcInsnNode(id));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new VarInsnNode(Opcodes.ILOAD, result));
            hook.add(
                    call(
                            "onBooleanValue",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
            method.instructions.insert(instruction, hook);
            changed = true;
        }
        return changed;
    }

    static boolean loadYawResult(MethodNode method, String id) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.name.equals("getYRot")
                    && invocation.desc.equals("()F")) {
                int result = method.maxLocals++;
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.FSTORE, result));
                hook.add(new LdcInsnNode(id));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new InsnNode(Opcodes.FCONST_0));
                hook.add(new VarInsnNode(Opcodes.FLOAD, result));
                hook.add(call("onFloatValue", "(Ljava/lang/String;Ljava/lang/Object;FF)F"));
                method.instructions.insert(instruction, hook);
                return true;
            }
        }
        return false;
    }

    static boolean loadHandAnimation(MethodNode method, String id) {
        // renderArmWithItem: hand=4, swingProgress=5, equipProgress=7, PoseStack=8.
        LabelNode continueVanilla = new LabelNode();
        InsnList start = new InsnList();
        start.add(new VarInsnNode(Opcodes.ALOAD, 8));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/vertex/PoseStack",
                        "pushPose",
                        "()V",
                        false));
        start.add(new LdcInsnNode(id));
        start.add(call("isHookActive", "(Ljava/lang/String;)Z"));
        start.add(new JumpInsnNode(Opcodes.IFEQ, continueVanilla));
        start.add(new LdcInsnNode(id));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new InsnNode(Opcodes.ICONST_4));
        start.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 8));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_1));
        start.add(new VarInsnNode(Opcodes.FLOAD, 5));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Float",
                        "valueOf",
                        "(F)Ljava/lang/Float;",
                        false));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_2));
        start.add(new VarInsnNode(Opcodes.FLOAD, 7));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Float",
                        "valueOf",
                        "(F)Ljava/lang/Float;",
                        false));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(new InsnNode(Opcodes.DUP));
        start.add(new InsnNode(Opcodes.ICONST_3));
        start.add(new VarInsnNode(Opcodes.ALOAD, 4));
        start.add(new InsnNode(Opcodes.AASTORE));
        start.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));

        // Submit the completed item pose directly to avoid composing a
        // second modern arm/use-animation transform over it.
        start.add(new LdcInsnNode(id + ".replace-vanilla"));
        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 4));
        start.add(new InsnNode(Opcodes.ICONST_0));
        start.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        start.add(new JumpInsnNode(Opcodes.IFEQ, continueVanilla));

        start.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.add(new VarInsnNode(Opcodes.ALOAD, 1));
        start.add(new VarInsnNode(Opcodes.ALOAD, 6));

        LabelNode leftHandContext = new LabelNode();
        LabelNode contextReady = new LabelNode();
        start.add(new VarInsnNode(Opcodes.ALOAD, 1));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/player/AbstractClientPlayer",
                        "getMainArm",
                        "()Lnet/minecraft/world/entity/HumanoidArm;",
                        false));
        start.add(
                new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/world/entity/HumanoidArm",
                        "RIGHT",
                        "Lnet/minecraft/world/entity/HumanoidArm;"));
        start.add(new JumpInsnNode(Opcodes.IF_ACMPNE, leftHandContext));
        start.add(
                new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/world/item/ItemDisplayContext",
                        "FIRST_PERSON_RIGHT_HAND",
                        "Lnet/minecraft/world/item/ItemDisplayContext;"));
        start.add(new JumpInsnNode(Opcodes.GOTO, contextReady));
        start.add(leftHandContext);
        start.add(
                new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/world/item/ItemDisplayContext",
                        "FIRST_PERSON_LEFT_HAND",
                        "Lnet/minecraft/world/item/ItemDisplayContext;"));
        start.add(contextReady);

        start.add(new VarInsnNode(Opcodes.ALOAD, 8));
        start.add(new VarInsnNode(Opcodes.ALOAD, 9));
        start.add(new VarInsnNode(Opcodes.ILOAD, 10));
        start.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/renderer/ItemInHandRenderer",
                        "renderItem",
                        "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
                                + "Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
                        false));
        start.add(new InsnNode(Opcodes.RETURN));
        start.add(continueVanilla);
        method.instructions.insert(start);

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
                    end.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    end.add(
                            new MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL,
                                    "com/mojang/blaze3d/vertex/PoseStack",
                                    "popPose",
                                    "()V",
                                    false));
                    return end;
                });
        return true;
    }
}
