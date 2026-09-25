package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import org.objectweb.asm.*;

import java.lang.reflect.Proxy;
import java.util.List;

/** Executes argument rewriting before the method body, including wide local-variable slots. */
final class FloatArgumentsVerification implements Opcodes {
    static void verify() throws Exception {
        String name = "com/blanoir/moons/agent/core/FloatArgumentsFixture";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "calculate", "(JFDF)F", null, null);
        method.visitCode();
        method.visitVarInsn(FLOAD, 3);
        method.visitLdcInsn(10f);
        method.visitInsn(FMUL);
        method.visitVarInsn(FLOAD, 6);
        method.visitInsn(FADD);
        method.visitInsn(FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        var mapping =
                new MappingService(
                        List.of(
                                new TargetMethod(
                                        "render.test-floats",
                                        List.of(name),
                                        List.of("calculate"),
                                        "(JFDF)F",
                                        TargetMethod.HookKind.FLOAT_ARGUMENTS)));
        var transformer = new MoonsTransformer(mapping);
        byte[] transformed = transformer.transform(null, name, writer.toByteArray());
        if (transformer.transform(null, name, transformed) != null)
            throw new AssertionError("Float arguments must not be injected twice");
        Class<?> fixture =
                new ClassLoader(FloatArgumentsVerification.class.getClassLoader()) {
                    Class<?> load() {
                        return defineClass(
                                name.replace('/', '.'), transformed, 0, transformed.length);
                    }
                }.load();
        Object instance = fixture.getConstructor().newInstance();
        var calculate =
                fixture.getMethod("calculate", long.class, float.class, double.class, float.class);
        boolean[] active = {true};
        int[] calls = {0};
        RuntimeBridge bridge =
                (RuntimeBridge)
                        Proxy.newProxyInstance(
                                RuntimeBridge.class.getClassLoader(),
                                new Class[] {RuntimeBridge.class},
                                (proxy, call, args) -> {
                                    if (call.getName().equals("isHookActive")) return active[0];
                                    if (call.getName().equals("onFloatValue")) {
                                        if (!args[0].equals("render.test-floats")
                                                || args[1] != instance)
                                            throw new AssertionError("Float hook identity");
                                        calls[0]++;
                                        float index = (float) args[2];
                                        float value = (float) args[3];
                                        if (index == 1 && value == 2) return 5f;
                                        if (index == 3 && value == 3) return 7f;
                                        throw new AssertionError("Float hook argument index/value");
                                    }
                                    return null;
                                });
        RuntimeBridge previous = AgentBridge.install(bridge);
        try {
            if ((float) calculate.invoke(instance, 123L, 2f, 456d, 3f) != 57f || calls[0] != 2)
                throw new AssertionError("Method body must use rewritten arguments");
            active[0] = false;
            if ((float) calculate.invoke(instance, 123L, 2f, 456d, 3f) != 23f || calls[0] != 2)
                throw new AssertionError("Disabled float hook must preserve arguments");
        } finally {
            AgentBridge.install(previous);
        }
    }
}
