package com.blanoir.moons.loader.common.asm.hooks;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.function.Supplier;

/** Typed argument/return adapters without linking the agent to Minecraft or LWJGL. */
final class Generic189Hooks implements Opcodes {
    private static final String BRIDGE = "com/blanoir/moons/api/bridge/AgentBridge";

    private Generic189Hooks() {}

    static boolean install(MethodNode m, String id, String kind) {
        if (kind.equals("INPUT_LOOPS")) return inputLoops(m);
        if (kind.equals("ENTER") || kind.equals("EXIT") || kind.equals("SCOPE")) {
            Supplier<InsnList> end = () -> notification(m, kind.equals("SCOPE") ? id + ".end" : id);
            if (kind.equals("EXIT")) atReturns(m, end);
            else if (kind.equals("SCOPE")) scope(m, notification(m, id), end);
            else m.instructions.insert(notification(m, id));
            return true;
        }
        if (kind.equals("GATE") || kind.equals("GATE_APPLY")) {
            InsnList code = prefix(m, id);
            code.add(new InsnNode(ICONST_1));
            call(
                    code,
                    "onBooleanValue",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z");
            LabelNode pass = new LabelNode();
            code.add(new JumpInsnNode(IFNE, pass));
            defaultReturn(code, Type.getReturnType(m.desc));
            code.add(pass);
            if (kind.equals("GATE_APPLY")) {
                for (AbstractInsnNode ins : m.instructions.toArray())
                    if (ins instanceof MethodInsnNode call
                            && call.getOpcode() == INVOKESTATIC
                            && (call.owner.equals("net/minecraft/network/PacketThreadUtil")
                                    || call.owner.equals("fh"))) {
                        m.instructions.insert(ins, code);
                        return true;
                    }
                throw new IllegalStateException("Missing game-thread handoff for " + id);
            }
            m.instructions.insert(code);
            return true;
        }
        if (kind.equals("RETURN")) {
            Type type = Type.getReturnType(m.desc);
            if (type.getSort() == Type.VOID) throw new IllegalArgumentException(id);
            int slot = m.maxLocals;
            m.maxLocals += type.getSize();
            for (AbstractInsnNode ins : m.instructions.toArray())
                if (ins.getOpcode() == type.getOpcode(IRETURN)) {
                    InsnList code = new InsnList();
                    code.add(new VarInsnNode(type.getOpcode(ISTORE), slot));
                    code.add(prefix(m, id));
                    code.add(new VarInsnNode(type.getOpcode(ILOAD), slot));
                    box(code, type);
                    call(
                            code,
                            "onObjectValue",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
                    unbox(code, type);
                    m.instructions.insertBefore(ins, code);
                }
            return true;
        }
        if (kind.startsWith("ARG:")) {
            int index = Integer.parseInt(kind.substring(4));
            Type[] types = Type.getArgumentTypes(m.desc);
            Type type = types[index];
            int slot = argumentSlot(m, index);
            InsnList code = prefix(m, id);
            code.add(new VarInsnNode(type.getOpcode(ILOAD), slot));
            box(code, type);
            call(
                    code,
                    "onObjectValue",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            unbox(code, type);
            code.add(new VarInsnNode(type.getOpcode(ISTORE), slot));
            m.instructions.insert(code);
            return true;
        }
        if (kind.equals("ARGS")) {
            InsnList code = new InsnList();
            code.add(new LdcInsnNode(id));
            owner(code, m);
            code.add(new InsnNode(ACONST_NULL));
            arguments(code, m);
            call(
                    code,
                    "onObjectValue",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            code.add(new TypeInsnNode(CHECKCAST, "[Ljava/lang/Object;"));
            int local = m.maxLocals++;
            code.add(new VarInsnNode(ASTORE, local));
            Type[] types = Type.getArgumentTypes(m.desc);
            for (int i = 0; i < types.length; i++) {
                code.add(new VarInsnNode(ALOAD, local));
                code.add(new LdcInsnNode(i));
                code.add(new InsnNode(AALOAD));
                unbox(code, types[i]);
                code.add(new VarInsnNode(types[i].getOpcode(ISTORE), argumentSlot(m, i)));
            }
            m.instructions.insert(code);
            return true;
        }
        throw new IllegalArgumentException(kind);
    }

    private static boolean inputLoops(MethodNode m) {
        boolean changed = false;
        for (AbstractInsnNode ins : m.instructions.toArray())
            if (ins instanceof MethodInsnNode call
                    && call.getOpcode() == INVOKESTATIC
                    && call.name.equals("next")
                    && call.desc.equals("()Z")
                    && (call.owner.equals("org/lwjgl/input/Keyboard")
                            || call.owner.equals("org/lwjgl/input/Mouse"))) {
                LabelNode again = new LabelNode(), done = new LabelNode();
                m.instructions.insertBefore(ins, again);
                InsnList code = new InsnList();
                code.add(new InsnNode(DUP));
                code.add(new JumpInsnNode(IFEQ, done));
                code.add(new InsnNode(POP));
                code.add(
                        new LdcInsnNode(
                                call.owner.endsWith("Keyboard")
                                        ? "input.keyboard-event"
                                        : "input.mouse-event"));
                owner(code, m);
                code.add(new InsnNode(ACONST_NULL));
                code.add(new InsnNode(ICONST_1));
                call(
                        code,
                        "onBooleanValue",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Z)Z");
                code.add(new JumpInsnNode(IFEQ, again));
                code.add(new InsnNode(ICONST_1));
                code.add(done);
                m.instructions.insert(ins, code);
                changed = true;
            }
        return changed;
    }

    private static void scope(MethodNode m, InsnList head, Supplier<InsnList> end) {
        LabelNode start = new LabelNode(), stop = new LabelNode(), handler = new LabelNode();
        head.add(start);
        m.instructions.insert(head);
        atReturns(m, end);
        m.instructions.add(stop);
        m.instructions.add(handler);
        int local = m.maxLocals++;
        m.instructions.add(new VarInsnNode(ASTORE, local));
        m.instructions.add(end.get());
        m.instructions.add(new VarInsnNode(ALOAD, local));
        m.instructions.add(new InsnNode(ATHROW));
        m.tryCatchBlocks.add(new TryCatchBlockNode(start, stop, handler, null));
    }

    private static InsnList notification(MethodNode m, String id) {
        InsnList c = prefix(m, id);
        call(c, "onVoidHook", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V");
        return c;
    }

    private static InsnList prefix(MethodNode m, String id) {
        InsnList c = new InsnList();
        c.add(new LdcInsnNode(id));
        owner(c, m);
        arguments(c, m);
        return c;
    }

    private static void owner(InsnList c, MethodNode m) {
        c.add((m.access & ACC_STATIC) == 0 ? new VarInsnNode(ALOAD, 0) : new InsnNode(ACONST_NULL));
    }

    private static int argumentSlot(MethodNode m, int index) {
        int slot = (m.access & ACC_STATIC) == 0 ? 1 : 0;
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int i = 0; i < index; i++) slot += args[i].getSize();
        return slot;
    }

    private static void arguments(InsnList c, MethodNode m) {
        Type[] args = Type.getArgumentTypes(m.desc);
        c.add(new LdcInsnNode(args.length));
        c.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < args.length; i++) {
            c.add(new InsnNode(DUP));
            c.add(new LdcInsnNode(i));
            c.add(new VarInsnNode(args[i].getOpcode(ILOAD), argumentSlot(m, i)));
            box(c, args[i]);
            c.add(new InsnNode(AASTORE));
        }
    }

    private static void atReturns(MethodNode m, Supplier<InsnList> code) {
        for (AbstractInsnNode ins : m.instructions.toArray())
            if (ins.getOpcode() >= IRETURN && ins.getOpcode() <= RETURN)
                m.instructions.insertBefore(ins, code.get());
    }

    private static void defaultReturn(InsnList c, Type t) {
        switch (t.getSort()) {
            case Type.VOID -> {}
            case Type.LONG -> c.add(new InsnNode(LCONST_0));
            case Type.FLOAT -> c.add(new InsnNode(FCONST_0));
            case Type.DOUBLE -> c.add(new InsnNode(DCONST_0));
            case Type.OBJECT, Type.ARRAY -> c.add(new InsnNode(ACONST_NULL));
            default -> c.add(new InsnNode(ICONST_0));
        }
        c.add(new InsnNode(t.getOpcode(IRETURN)));
    }

    private static String wrapper(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.CHAR -> "java/lang/Character";
            case Type.INT -> "java/lang/Integer";
            case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }

    private static void box(InsnList c, Type t) {
        String w = wrapper(t);
        if (w != null)
            c.add(
                    new MethodInsnNode(
                            INVOKESTATIC,
                            w,
                            "valueOf",
                            "(" + t.getDescriptor() + ")L" + w + ";",
                            false));
    }

    private static void unbox(InsnList c, Type t) {
        String w = wrapper(t);
        if (w == null) c.add(new TypeInsnNode(CHECKCAST, t.getInternalName()));
        else {
            c.add(new TypeInsnNode(CHECKCAST, w));
            String name =
                    switch (t.getSort()) {
                        case Type.BOOLEAN -> "booleanValue";
                        case Type.BYTE -> "byteValue";
                        case Type.SHORT -> "shortValue";
                        case Type.CHAR -> "charValue";
                        case Type.INT -> "intValue";
                        case Type.FLOAT -> "floatValue";
                        case Type.LONG -> "longValue";
                        case Type.DOUBLE -> "doubleValue";
                        default -> throw new IllegalArgumentException();
                    };
            c.add(new MethodInsnNode(INVOKEVIRTUAL, w, name, "()" + t.getDescriptor(), false));
        }
    }

    private static void call(InsnList c, String name, String desc) {
        c.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, name, desc, false));
    }
}
