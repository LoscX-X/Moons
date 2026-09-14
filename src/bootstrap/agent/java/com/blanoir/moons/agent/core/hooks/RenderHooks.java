package com.blanoir.moons.agent.core.hooks;

import static com.blanoir.moons.agent.core.hooks.HookInstructions.appendVoidCancellation;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.beforeReturns;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.call;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.guardHook;
import static com.blanoir.moons.agent.core.hooks.HookInstructions.thisCall;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** HUD, world, render-state and equipment hook placement. */
final class RenderHooks {
    private RenderHooks() {}

    static boolean loadHud(MethodNode method) {
        int extractorSlot = -1;
        if (method.desc.equals("(Lnet/minecraft/client/DeltaTracker;ZZ)V")) {
            boolean constructingExtractor = false;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode type
                        && instruction.getOpcode() == Opcodes.NEW
                        && type.desc.equals("net/minecraft/client/gui/GuiGraphicsExtractor")) {
                    constructingExtractor = true;
                    continue;
                }
                if (constructingExtractor
                        && instruction instanceof VarInsnNode variable
                        && instruction.getOpcode() == Opcodes.ASTORE) {
                    extractorSlot = variable.var;
                    break;
                }
            }
            if (extractorSlot < 0) return false;
        }
        int resolvedExtractorSlot = extractorSlot;
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    if (method.desc.equals("(Lnet/minecraft/client/DeltaTracker;ZZ)V")) {
                        // 26.2 moved GuiGraphicsExtractor from a method parameter to a
                        // local created inside Gui.extractRenderState.
                        hook.add(new VarInsnNode(Opcodes.ALOAD, resolvedExtractorSlot));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    } else {
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    }
                    hook.add(
                            call(
                                    "onHudRender",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    return hook;
                });
        return true;
    }

    static boolean loadScoreboard(MethodNode method, String id) {
        LabelNode proceed = new LabelNode();
        InsnList hook = new InsnList();
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
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new InsnNode(Opcodes.AASTORE));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(proceed);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(guardHook(id, hook));
        return true;
    }

    static boolean loadBeforeGpuPresent(MethodNode method, String id) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals("com/mojang/blaze3d/systems/GpuSurface")
                    && invocation.name.equals("present")
                    && invocation.desc.equals("()V")) {
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

    static boolean loadWorldRender(MethodNode method) {
        int poseStackSlot = -1;
        AbstractInsnNode poseCreation = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.desc.equals("com/mojang/blaze3d/vertex/PoseStack")) {
                poseCreation = instruction;
                continue;
            }
            if (poseCreation != null
                    && instruction instanceof VarInsnNode variable
                    && instruction.getOpcode() == Opcodes.ASTORE) {
                poseStackSlot = variable.var;
                break;
            }
        }
        boolean collectLoaded = false;
        boolean translucentLoaded = false;
        int renderGroupOrdinal = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!collectLoaded
                    && instruction instanceof LdcInsnNode constant
                    && "renderSolidFeatures".equals(constant.cst)) {
                AbstractInsnNode insertionPoint = constant.getPrevious();
                while (insertionPoint != null
                        && (insertionPoint.getType() == AbstractInsnNode.LABEL
                                || insertionPoint.getType() == AbstractInsnNode.LINE
                                || insertionPoint.getType() == AbstractInsnNode.FRAME)) {
                    insertionPoint = insertionPoint.getPrevious();
                }
                if (insertionPoint == null) insertionPoint = constant;
                else if (insertionPoint.getOpcode() == Opcodes.ALOAD) {
                    // Insert before the profiler load so the operand stack is empty.
                } else {
                    insertionPoint = constant;
                }
                method.instructions.insertBefore(insertionPoint, worldRenderCall(poseStackSlot, 0));
                collectLoaded = true;
            }
            if (instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals(
                            "net/minecraft/client/renderer/chunk/ChunkSectionsToRender")
                    && invocation.name.equals("renderGroup")) {
                if (renderGroupOrdinal++ == 1) {
                    method.instructions.insert(instruction, worldRenderCall(poseStackSlot, 1));
                    translucentLoaded = true;
                }
            }
        }
        return collectLoaded && translucentLoaded;
    }

    private static InsnList worldRenderCall(int poseStackSlot, int stage) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (poseStackSlot >= 0) {
            hook.add(new VarInsnNode(Opcodes.ALOAD, poseStackSlot));
        } else {
            hook.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/blaze3d/vertex/PoseStack"));
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESPECIAL,
                            "com/mojang/blaze3d/vertex/PoseStack",
                            "<init>",
                            "()V",
                            false));
        }
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new InsnNode(stage == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
        hook.add(
                call(
                        "onWorldRender",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"));
        return hook;
    }

    static boolean loadRenderState(MethodNode method) {
        beforeReturns(
                method,
                Opcodes.RETURN,
                () -> {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    hook.add(new VarInsnNode(Opcodes.FLOAD, 3));
                    hook.add(call("onRenderState", "(Ljava/lang/Object;Ljava/lang/Object;F)V"));
                    return hook;
                });
        return true;
    }

    static boolean loadRendererClose(MethodNode method) {
        beforeReturns(method, Opcodes.RETURN, () -> thisCall("onRendererClose"));
        return true;
    }

    /** Draws bundled trims after the armor layers, before the vanilla atlas lookup. */
    static boolean loadTrimRender(MethodNode method, String id) {
        AbstractInsnNode lookup = null;
        int orderSlot = -1;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.name.equals("trimSpriteLookup")
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.getPrevious() instanceof VarInsnNode owner
                    && owner.getOpcode() == Opcodes.ALOAD
                    && owner.var == 0) {
                lookup = owner;
            }
            if (lookup != null
                    && instruction instanceof MethodInsnNode invocation
                    && invocation.owner.equals("net/minecraft/client/renderer/SubmitNodeCollector")
                    && invocation.name.equals("order")
                    && invocation.getPrevious() instanceof IincInsnNode increment) {
                orderSlot = increment.var;
                break;
            }
        }
        if (lookup == null || orderSlot < 0) return false;

        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        hook.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int[] slots = {1, 2, 3, 4, 5, 6, 7, 8, 10, orderSlot};
        for (int index = 0; index < slots.length; index++) {
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new IntInsnNode(Opcodes.BIPUSH, index));
            hook.add(new VarInsnNode(index < 7 ? Opcodes.ALOAD : Opcodes.ILOAD, slots[index]));
            if (index >= 7) {
                hook.add(
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "java/lang/Integer",
                                "valueOf",
                                "(I)Ljava/lang/Integer;",
                                false));
            }
            hook.add(new InsnNode(Opcodes.AASTORE));
        }
        hook.add(new InsnNode(Opcodes.ICONST_1));
        hook.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(hook);
        method.instructions.insertBefore(lookup, guardHook(id, hook));
        return true;
    }

    /** Replaces the fifth declared argument of EquipmentLayerRenderer#renderLayers. */
    static boolean loadItemStackArgument5(MethodNode method, String id) {
        org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
        if (arguments.length < 5
                || !arguments[4].getDescriptor().equals("Lnet/minecraft/world/item/ItemStack;")) {
            return false;
        }

        int itemStackSlot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int index = 0; index < 4; index++) itemStackSlot += arguments[index].getSize();
        int firstArgumentSlot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;

        InsnList hook = new InsnList();
        hook.add(new LdcInsnNode(id));
        hook.add(
                (method.access & Opcodes.ACC_STATIC) == 0
                        ? new VarInsnNode(Opcodes.ALOAD, 0)
                        : new InsnNode(Opcodes.ACONST_NULL));
        hook.add(new VarInsnNode(Opcodes.ALOAD, firstArgumentSlot));
        hook.add(new VarInsnNode(Opcodes.ALOAD, itemStackSlot));
        hook.add(
                call(
                        "onObjectValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/item/ItemStack"));
        hook.add(new VarInsnNode(Opcodes.ASTORE, itemStackSlot));
        method.instructions.insert(hook);
        return true;
    }

    /** Replaces the ItemStack returned by LocalPlayer#getMainHandItem inside a target method. */
    static boolean loadObjectInvocationReturn(MethodNode method, String id) {
        String invocationName = id.contains("hud") ? "getSelectedItem" : "getMainHandItem";
        boolean changed = false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode invocation)
                    || !invocation.name.equals(invocationName)
                    || !invocation.desc.equals("()Lnet/minecraft/world/item/ItemStack;")) continue;
            int result = method.maxLocals++;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ASTORE, result));
            hook.add(new LdcInsnNode(id));
            hook.add(
                    (method.access & Opcodes.ACC_STATIC) == 0
                            ? new VarInsnNode(Opcodes.ALOAD, 0)
                            : new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new InsnNode(Opcodes.ACONST_NULL));
            hook.add(new VarInsnNode(Opcodes.ALOAD, result));
            hook.add(
                    call(
                            "onObjectValue",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
            hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/item/ItemStack"));
            method.instructions.insert(instruction, hook);
            changed = true;
        }
        return changed;
    }
}
