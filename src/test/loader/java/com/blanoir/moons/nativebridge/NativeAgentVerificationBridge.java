package com.blanoir.moons.nativebridge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Small deterministic ASM transformer used only by the native-agent verification JVM. */
public final class NativeAgentVerificationBridge {
    private static final String TARGET =
            "com/blanoir/moons/nativebridge/NativeAgentVerificationTarget";

    private NativeAgentVerificationBridge() {}

    public static byte[] transform(
            String className, ClassLoader loader, byte[] classBytes, int flags) {
        if (!TARGET.equals(className)) return null;

        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        MethodVisitor delegate =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!name.equals("message") || !descriptor.equals("()Ljava/lang/String;")) {
                            return delegate;
                        }
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitLdcInsn(Object value) {
                                super.visitLdcInsn(
                                        "ORIGINAL".equals(value)
                                                ? "TRANSFORMED_BY_JVMTI_ASM"
                                                : value);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }
}
