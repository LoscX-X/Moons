package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.bridge.RuntimeBridge;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

/** Links loader-local hooks to their delegate once, without reflective dispatch or boxing. */
final class RuntimeBridgeAdapter {
    static final String NAME = "com.blanoir.moons.api.bridge.MethodHandleRuntimeBridge";
    private static final String INTERNAL_NAME = NAME.replace('.', '/');
    private static final Method[] METHODS =
            Arrays.stream(RuntimeBridge.class.getDeclaredMethods())
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .sorted(
                            Comparator.comparing(Method::getName)
                                    .thenComparing(method -> Type.getMethodDescriptor(method)))
                    .toArray(Method[]::new);

    private RuntimeBridgeAdapter() {}

    static MethodHandle[] handles(RuntimeBridge delegate) throws IllegalAccessException {
        MethodHandle[] handles = new MethodHandle[METHODS.length];
        for (int index = 0; index < handles.length; index++) {
            handles[index] = MethodHandles.lookup().unreflect(METHODS[index]).bindTo(delegate);
        }
        return handles;
    }

    static byte[] classBytes() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                INTERNAL_NAME,
                null,
                "java/lang/Object",
                new String[] {Type.getInternalName(RuntimeBridge.class)});
        MethodVisitor constructor =
                writer.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "<init>",
                        "([Ljava/lang/invoke/MethodHandle;)V",
                        null,
                        null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int index = 0; index < METHODS.length; index++) {
            String field = "target" + index;
            writer.visitField(
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                            field,
                            "Ljava/lang/invoke/MethodHandle;",
                            null,
                            null)
                    .visitEnd();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitVarInsn(Opcodes.ALOAD, 1);
            constructor.visitLdcInsn(index);
            constructor.visitInsn(Opcodes.AALOAD);
            constructor.visitFieldInsn(
                    Opcodes.PUTFIELD, INTERNAL_NAME, field, "Ljava/lang/invoke/MethodHandle;");

            Method source = METHODS[index];
            String descriptor = Type.getMethodDescriptor(source);
            MethodVisitor method =
                    writer.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                            source.getName(),
                            descriptor,
                            null,
                            null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(
                    Opcodes.GETFIELD, INTERNAL_NAME, field, "Ljava/lang/invoke/MethodHandle;");
            int local = 1;
            for (Type argument : Type.getArgumentTypes(descriptor)) {
                method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
                local += argument.getSize();
            }
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "invokeExact",
                    descriptor,
                    false);
            method.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
