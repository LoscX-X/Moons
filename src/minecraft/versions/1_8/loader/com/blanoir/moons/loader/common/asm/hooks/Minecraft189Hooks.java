package com.blanoir.moons.loader.common.asm.hooks;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.function.Supplier;

/** 1.8.9 method layouts adapted to the existing loader-neutral bridge ABI. */
public final class Minecraft189Hooks implements Opcodes {
    private static final String BRIDGE = "com/blanoir/moons/api/bridge/AgentBridge";

    private Minecraft189Hooks() {}

    public static boolean install(MethodNode method, String id, String kind) {
        switch (kind) {
            case "TICK" -> startEnd(method, "onClientTickStart", "onClientTickEnd");
            case "POSITION" -> startEnd(method, "onPlayerPositionStart", "onPlayerPositionEnd");
            case "FRAME" -> {
                InsnList code = self();
                code.add(new VarInsnNode(FLOAD, 1));
                boxFloat(code);
                call(code, "onFrame", "(Ljava/lang/Object;Ljava/lang/Object;)V");
                method.instructions.insert(code);
            }
            case "HUD" ->
                    returns(
                            method,
                            RETURN,
                            () -> {
                                InsnList code = self();
                                code.add(new InsnNode(ACONST_NULL));
                                code.add(new VarInsnNode(FLOAD, 1));
                                boxFloat(code);
                                call(
                                        code,
                                        "onHudRender",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
                                return code;
                            });
            case "WORLD" -> {
                boolean found = false;
                for (AbstractInsnNode instruction : method.instructions.toArray())
                    if (instruction instanceof LdcInsnNode constant
                            && "hand".equals(constant.cst)) {
                        InsnList code = self();
                        code.add(new InsnNode(ACONST_NULL));
                        code.add(new VarInsnNode(FLOAD, 2));
                        boxFloat(code);
                        code.add(new InsnNode(ICONST_1));
                        call(
                                code,
                                "onWorldRender",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V");
                        method.instructions.insertBefore(instruction, code);
                        found = true;
                    }
                if (!found) throw new IllegalStateException("Missing world/hand boundary");
            }
            case "HELD_ITEM" -> {
                boolean found = false;
                for (AbstractInsnNode instruction : method.instructions.toArray())
                    if (instruction instanceof MethodInsnNode invoke
                            && ((invoke.owner.equals("net/minecraft/entity/player/InventoryPlayer")
                                            && invoke.name.equals("getCurrentItem")
                                            && invoke.desc.equals(
                                                    "()Lnet/minecraft/item/ItemStack;"))
                                    || (invoke.owner.equals("wm")
                                            && invoke.name.equals("h")
                                            && invoke.desc.equals("()Lzx;")))) {
                        int slot = method.maxLocals++;
                        InsnList code = new InsnList();
                        code.add(new VarInsnNode(ASTORE, slot));
                        code.add(new LdcInsnNode(id));
                        code.add(new VarInsnNode(ALOAD, 0));
                        code.add(new InsnNode(ACONST_NULL));
                        code.add(new VarInsnNode(ALOAD, slot));
                        call(
                                code,
                                "onObjectValue",
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                        code.add(
                                new TypeInsnNode(
                                        CHECKCAST,
                                        org.objectweb.asm.Type.getReturnType(invoke.desc)
                                                .getInternalName()));
                        method.instructions.insert(instruction, code);
                        found = true;
                    }
                if (!found)
                    throw new IllegalStateException("Missing inventory held item read: " + id);
            }
            case "ATTACK", "USE" -> {
                String action = kind.equals("ATTACK") ? "onAttack" : "onUse";
                InsnList code = self();
                call(code, action, "(Ljava/lang/Object;)Z");
                cancel(code);
                scope(
                        method,
                        code,
                        () -> {
                            InsnList end = self();
                            call(end, action + "End", "(Ljava/lang/Object;)V");
                            return end;
                        });
            }
            case "PLAYER_UPDATE" -> {
                InsnList code = self();
                call(code, "onPlayerUpdate", "(Ljava/lang/Object;)Z");
                cancel(code);
                method.instructions.insert(code);
            }
            case "MOVE_INPUT" ->
                    returns(
                            method,
                            RETURN,
                            () -> {
                                InsnList code = self();
                                call(code, "onMoveInput", "(Ljava/lang/Object;)V");
                                return code;
                            });
            case "PACKET_SEND" -> {
                returns(
                        method,
                        RETURN,
                        () -> {
                            InsnList code = self();
                            code.add(new VarInsnNode(ALOAD, 1));
                            call(code, "onPacketSent", "(Ljava/lang/Object;Ljava/lang/Object;)V");
                            return code;
                        });
                InsnList code = self();
                code.add(new VarInsnNode(ALOAD, 1));
                call(code, "onPacketSend", "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                cancel(code);
                method.instructions.insert(code);
            }
            case "PACKET_RECEIVE" -> {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 2));
                code.add(new VarInsnNode(ALOAD, 0));
                call(code, "onPacketReceive", "(Ljava/lang/Object;Ljava/lang/Object;)Z");
                cancel(code);
                method.instructions.insert(code);
            }
            case "PACKET_APPLY" -> {
                // Run after vanilla's thread handoff, never twice on Netty and the client thread.
                MethodInsnNode handoff = null;
                for (AbstractInsnNode instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode invoke
                            && invoke.getOpcode() == INVOKESTATIC
                            && invoke.desc.startsWith("(L")
                            && invoke.desc.endsWith(")V")
                            && org.objectweb.asm.Type.getArgumentTypes(invoke.desc).length == 3) {
                        if (invoke.owner.equals("net/minecraft/network/PacketThreadUtil")
                                || invoke.owner.equals("fh")) {
                            handoff = invoke;
                            break;
                        }
                    }
                if (handoff == null) return false;
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 1));
                code.add(new VarInsnNode(ALOAD, 0));
                call(code, "onPacketApply", "(Ljava/lang/Object;Ljava/lang/Object;)V");
                method.instructions.insert(handoff, code);
            }
            case "VOID_HEAD", "VOID_RETURN" -> {
                Supplier<InsnList> code =
                        () -> {
                            InsnList list = new InsnList();
                            list.add(new LdcInsnNode(id));
                            list.add(new VarInsnNode(ALOAD, 0));
                            list.add(new InsnNode(ACONST_NULL));
                            call(
                                    list,
                                    "onVoidHook",
                                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V");
                            return list;
                        };
                if (kind.equals("VOID_HEAD")) method.instructions.insert(code.get());
                else returns(method, RETURN, code);
            }
            case "FLOAT_RETURN" ->
                    returns(
                            method,
                            FRETURN,
                            () -> {
                                int slot = method.maxLocals++;
                                InsnList code = new InsnList();
                                code.add(new VarInsnNode(FSTORE, slot));
                                code.add(new LdcInsnNode(id));
                                code.add(new VarInsnNode(ALOAD, 0));
                                code.add(new InsnNode(FCONST_0));
                                code.add(new VarInsnNode(FLOAD, slot));
                                call(
                                        code,
                                        "onFloatValue",
                                        "(Ljava/lang/String;Ljava/lang/Object;FF)F");
                                return code;
                            });
            case "GATE_ARG" -> {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode(id));
                code.add(new VarInsnNode(ALOAD, 0));
                code.add(new VarInsnNode(ALOAD, 1));
                code.add(new InsnNode(ICONST_1));
                call(
                        code,
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z");
                cancel(code);
                method.instructions.insert(code);
            }
            default -> {
                return Generic189Hooks.install(method, id, kind);
            }
        }
        return true;
    }

    private static void startEnd(MethodNode method, String start, String end) {
        InsnList head = self();
        call(head, start, "(Ljava/lang/Object;)V");
        scope(
                method,
                head,
                () -> {
                    InsnList code = self();
                    call(code, end, "(Ljava/lang/Object;)V");
                    return code;
                });
    }

    private static void scope(MethodNode method, InsnList head, Supplier<InsnList> end) {
        // Entry cancellation stays outside the scope; accepted actions restore even on failure.
        LabelNode start = new LabelNode(), stop = new LabelNode(), handler = new LabelNode();
        returns(method, RETURN, end);
        head.add(start);
        method.instructions.insert(head);
        method.instructions.add(stop);
        method.instructions.add(handler);
        int slot = method.maxLocals++;
        method.instructions.add(new VarInsnNode(ASTORE, slot));
        method.instructions.add(end.get());
        method.instructions.add(new VarInsnNode(ALOAD, slot));
        method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, stop, handler, null));
    }

    private static InsnList self() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(ALOAD, 0));
        return code;
    }

    private static void boxFloat(InsnList code) {
        code.add(
                new MethodInsnNode(
                        INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
    }

    private static void call(InsnList code, String name, String descriptor) {
        code.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, name, descriptor, false));
    }

    private static void cancel(InsnList code) {
        LabelNode next = new LabelNode();
        code.add(new JumpInsnNode(IFNE, next));
        code.add(new InsnNode(RETURN));
        code.add(next);
    }

    private static void returns(MethodNode method, int opcode, Supplier<InsnList> code) {
        for (AbstractInsnNode instruction : method.instructions.toArray())
            if (instruction.getOpcode() == opcode)
                method.instructions.insertBefore(instruction, code.get());
    }
}
