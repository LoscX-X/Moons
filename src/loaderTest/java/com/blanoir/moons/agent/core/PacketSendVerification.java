package com.blanoir.moons.agent.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Executes the actual send transform, including cancellation and multiple returns. */
public final class PacketSendVerification {
    private static boolean cancel;
    private static int posts;
    private static int bodies;
    private static final String OWNER = PacketSendVerification.class.getName().replace('.', '/');

    static void verify() throws Exception {
        MethodNode send = new MethodNode(Opcodes.ACC_PUBLIC, "send", "(Ljava/lang/Object;)V", null, null);
        send.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        send.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                OWNER, "sentBody", "(Ljava/lang/Object;)V", false));
        LabelNode nonNull = new LabelNode();
        send.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        send.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, nonNull));
        send.instructions.add(new InsnNode(Opcodes.RETURN));
        send.instructions.add(nonNull);
        send.instructions.add(new InsnNode(Opcodes.RETURN));
        send.maxLocals = 2;
        Method hook = MoonsTransformer.class.getDeclaredMethod("loadPacketSend", MethodNode.class);
        hook.setAccessible(true);
        require((boolean) hook.invoke(null, send), "send hook installed");
        for (AbstractInsnNode insn : send.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("com/blanoir/moons/api/bridge/AgentBridge")) call.owner = OWNER;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "PacketSendFixture", null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        send.accept(writer);
        writer.visitEnd();
        byte[] code = writer.toByteArray();
        Class<?> fixture = new ClassLoader(PacketSendVerification.class.getClassLoader()) {
            Class<?> define() { return defineClass("PacketSendFixture", code, 0, code.length); }
        }.define();
        Object instance = fixture.getConstructor().newInstance();
        Method invoke = fixture.getMethod("send", Object.class);

        cancel = true;
        bodies = posts = 0;
        invoke.invoke(instance, new Object());
        require(bodies == 0 && posts == 0, "cancelled send must not emit POST");
        cancel = false;
        for (Object packet : new Object[] {null, new Object()}) {
            bodies = posts = 0;
            invoke.invoke(instance, packet);
            require(bodies == 1 && posts == 1, "each original return emits POST once");
        }
        bodies = posts = 0;
        try {
            invoke.invoke(instance, new IllegalStateException("send failed"));
            throw new AssertionError("fixture must throw");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalStateException, "original exception propagates");
            require(bodies == 1 && posts == 0, "exceptional send must not emit POST");
        }
        System.out.println("PACKET_SEND_VERIFIED cancelled=0/0 normal=1/1 exceptional=1/0");
    }

    public static boolean onPacketSend(Object connection, Object packet) { return !cancel; }
    public static void onPacketSent(Object connection, Object packet) { posts++; }
    public static void sentBody(Object packet) {
        bodies++;
        if (packet instanceof RuntimeException exception) throw exception;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
