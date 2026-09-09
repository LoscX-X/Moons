package com.blanoir.moons.agent.core;

import static com.blanoir.moons.agent.core.HookInstructions.*;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Injection boundaries verified against 26.3-pre-3's extracted renderer. */
final class VersionTransformer {
    private VersionTransformer() {}

    static boolean load(MethodNode method, String id) {
        return switch (id) {
            case "client.world-render" -> world(method);
            case "render.present" -> present(method, id);
            case "render.chams-frame" -> chamsFrame(method, id);
            case "render.chams-mark-item" -> markItems(method, id);
            case "render.chams-item" -> item(method, id);
            case "render.chams-equipment", "render.chams-cape" ->
                    remap(method, id, "submitModel", 3);
            case "render.trim.direct" -> trim(method, id);
            case "render.silent-aura-animation" -> hand(method, id);
            default -> false;
        };
    }

    private static InsnList hook(String id, int argument) {
        InsnList out = new InsnList();
        out.add(new LdcInsnNode(id));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(
                argument < 0
                        ? new InsnNode(Opcodes.ACONST_NULL)
                        : new VarInsnNode(Opcodes.ALOAD, argument));
        out.add(call("onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
        return out;
    }

    private static boolean present(MethodNode method, String id) {
        for (AbstractInsnNode node : method.instructions.toArray()) {
            if (node instanceof MethodInsnNode invoke
                    && invoke.owner.equals("com/mojang/renderpearl/api/device/GpuSurface")
                    && invoke.name.equals("blitFromTexture")) {
                // Overlay the main target before either OpenGL or Vulkan copies it to the
                // swapchain.
                method.instructions.insertBefore(node, hook(id, -1));
                return true;
            }
        }
        return false;
    }

    private static InsnList worldCall(int stage) {
        InsnList out = new InsnList();
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/blaze3d/vertex/PoseStack"));
        out.add(new InsnNode(Opcodes.DUP));
        out.add(
                new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "com/mojang/blaze3d/vertex/PoseStack",
                        "<init>",
                        "()V",
                        false));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(
                new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/renderer/LevelRenderer",
                        "levelRenderState",
                        "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"));
        out.add(new InsnNode(stage == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
        out.add(
                call(
                        "onWorldRender",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"));
        return out;
    }

    private static boolean world(MethodNode method) {
        boolean collect = false, draw = false;
        for (AbstractInsnNode node : method.instructions.toArray()) {
            if (!(node instanceof MethodInsnNode invoke)) continue;
            if (invoke.owner.equals("net/minecraft/client/renderer/feature/FeatureRenderDispatcher")
                    && invoke.name.equals("prepareFrame")) {
                method.instructions.insertBefore(node, worldCall(0));
                collect = true;
            }
            if (invoke.owner.equals("org/joml/Matrix4fStack") && invoke.name.equals("popMatrix")) {
                method.instructions.insertBefore(node, worldCall(1));
                draw = true;
            }
        }
        return collect && draw;
    }

    private static boolean chamsFrame(MethodNode method, String id) {
        method.instructions.insert(hook(id + ".begin", -1));
        for (AbstractInsnNode node : method.instructions.toArray()) {
            if (node instanceof MethodInsnNode invoke
                    && invoke.owner.equals("com/mojang/blaze3d/framegraph/FrameGraphBuilder")
                    && invoke.name.equals("execute")) {
                method.instructions.insert(node, hook(id + ".end", -1));
                return true;
            }
        }
        return false;
    }

    private static boolean markItems(MethodNode method, String id) {
        boolean changed = false;
        for (AbstractInsnNode node : method.instructions.toArray()) {
            if (node instanceof MethodInsnNode invoke
                    && invoke.name.equals("<init>")
                    && invoke.owner.equals(
                            "net/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit")) {
                // Both opaque and translucent submissions are constructed directly on the stack.
                int slot = method.maxLocals++;
                InsnList out = new InsnList();
                out.add(new InsnNode(Opcodes.DUP));
                out.add(new VarInsnNode(Opcodes.ASTORE, slot));
                out.add(hook(id, slot));
                method.instructions.insert(node, out);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean item(MethodNode method, String id) {
        if (!remap(method, id + ".type", "getVertexBuilder", 0)) return false;
        method.instructions.insert(hook(id, 1));
        beforeReturns(method, Opcodes.RETURN, () -> hook(id + ".end", 1));
        return true;
    }

    private static boolean remap(MethodNode method, String id, String name, int argument) {
        boolean changed = false;
        for (AbstractInsnNode node : method.instructions.toArray()) {
            if (!(node instanceof MethodInsnNode invoke) || !invoke.name.equals(name)) continue;
            Type[] types = Type.getArgumentTypes(invoke.desc);
            if (types.length <= argument
                    || !types[argument]
                            .getDescriptor()
                            .equals("Lnet/minecraft/client/renderer/rendertype/RenderType;"))
                continue;
            InsnList out = new InsnList();
            int[] slots = new int[types.length];
            for (int i = types.length - 1; i >= argument; i--) {
                slots[i] = method.maxLocals;
                method.maxLocals += types[i].getSize();
                out.add(new VarInsnNode(types[i].getOpcode(Opcodes.ISTORE), slots[i]));
            }
            out.add(new LdcInsnNode(id));
            out.add(new VarInsnNode(Opcodes.ALOAD, 0));
            out.add(new InsnNode(Opcodes.ACONST_NULL));
            out.add(new VarInsnNode(Opcodes.ALOAD, slots[argument]));
            out.add(
                    call(
                            "onObjectValue",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
            out.add(new TypeInsnNode(Opcodes.CHECKCAST, types[argument].getInternalName()));
            for (int i = argument + 1; i < types.length; i++)
                out.add(new VarInsnNode(types[i].getOpcode(Opcodes.ILOAD), slots[i]));
            method.instructions.insertBefore(node, out);
            changed = true;
        }
        return changed;
    }

    private static void arrayValue(InsnList out, int index, int slot, Type type) {
        out.add(new InsnNode(Opcodes.DUP));
        out.add(new IntInsnNode(Opcodes.BIPUSH, index));
        out.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
        if (type.equals(Type.FLOAT_TYPE))
            out.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Float",
                            "valueOf",
                            "(F)Ljava/lang/Float;",
                            false));
        else if (type.equals(Type.INT_TYPE))
            out.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/Integer",
                            "valueOf",
                            "(I)Ljava/lang/Integer;",
                            false));
        out.add(new InsnNode(Opcodes.AASTORE));
    }

    private static InsnList gate(String id, int size) {
        InsnList out = new InsnList();
        out.add(new LdcInsnNode(id));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new IntInsnNode(Opcodes.BIPUSH, size));
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        return out;
    }

    private static void finishGate(InsnList out) {
        out.add(new InsnNode(Opcodes.ICONST_1));
        out.add(
                call(
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
        appendVoidCancellation(out);
    }

    private static boolean hand(MethodNode method, String id) {
        Type[] args = Type.getArgumentTypes(method.desc);
        InsnList out = gate(id, args.length);
        int slot = 1;
        for (int i = 0; i < args.length; i++) {
            arrayValue(out, i, slot, args[i]);
            slot += args[i].getSize();
        }
        finishGate(out);
        method.instructions.insert(guardHook(id, out));
        return true;
    }

    private static boolean trim(MethodNode method, String id) {
        AbstractInsnNode lookup = null;
        int order = -1;
        for (LocalVariableNode local : method.localVariables)
            if (local.name.equals("nextOrder")) order = local.index;
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof FieldInsnNode field
                    && field.name.equals("trimTextureLookup")
                    && field.getOpcode() == Opcodes.GETFIELD) {
                lookup = node.getPrevious();
                break;
            }
        }
        if (lookup == null || order < 0) return false;
        InsnList out = gate(id, 10);
        int[] slots = {1, 2, 3, 4, 5, 6, 7, 8, 10, order};
        for (int i = 0; i < slots.length; i++)
            arrayValue(out, i, slots[i], i < 7 ? Type.getType(Object.class) : Type.INT_TYPE);
        finishGate(out);
        method.instructions.insertBefore(lookup, guardHook(id, out));
        return true;
    }
}
