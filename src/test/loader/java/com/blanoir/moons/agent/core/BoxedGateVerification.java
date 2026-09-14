package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.api.bridge.RuntimeBridge;

import org.objectweb.asm.*;

import java.lang.reflect.Proxy;
import java.util.*;

/** Executes the transformed gate with wide primitives, arrays, cancellation and disabled hooks. */
final class BoxedGateVerification implements Opcodes {
    static void verify() throws Exception {
        String name = "com/blanoir/moons/agent/core/GateFixture";
        String descriptor = "(Ljava/lang/Object;IJDFZCBS[I)V";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "calls", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "submit", descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(DUP);
        method.visitFieldInsn(GETFIELD, name, "calls", "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTFIELD, name, "calls", "I");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        var mapping =
                new MappingService(
                        List.of(
                                new TargetMethod(
                                        "render.test-boxed",
                                        List.of(name),
                                        List.of("submit"),
                                        descriptor,
                                        TargetMethod.HookKind.BOXED_ARGS_VOID_GATE)));
        byte[] transformed =
                new MoonsTransformer(mapping).transform(null, name, writer.toByteArray());
        Class<?> fixture =
                new ClassLoader(BoxedGateVerification.class.getClassLoader()) {
                    Class<?> load() {
                        return defineClass(
                                name.replace('/', '.'), transformed, 0, transformed.length);
                    }
                }.load();
        Object instance = fixture.getConstructor().newInstance();
        var submit =
                fixture.getMethod(
                        "submit",
                        Object.class,
                        int.class,
                        long.class,
                        double.class,
                        float.class,
                        boolean.class,
                        char.class,
                        byte.class,
                        short.class,
                        int[].class);
        Object[] expected = {
            new Object(),
            17,
            Long.MAX_VALUE,
            3.25d,
            4.5f,
            true,
            '中',
            (byte) -17,
            (short) 300,
            new int[] {7, 8}
        };
        Object[][] captured = {null};
        boolean[] allow = {true}, active = {true};
        RuntimeBridge bridge =
                (RuntimeBridge)
                        Proxy.newProxyInstance(
                                RuntimeBridge.class.getClassLoader(),
                                new Class[] {RuntimeBridge.class},
                                (proxy, call, args) -> {
                                    if (call.getName().equals("isHookActive")) return active[0];
                                    if (call.getName().equals("onBooleanValue")) {
                                        if (args[1] != instance)
                                            throw new AssertionError("Hook owner");
                                        captured[0] = (Object[]) args[2];
                                        return allow[0];
                                    }
                                    return null;
                                });
        RuntimeBridge previous = AgentBridge.install(bridge);
        try {
            submit.invoke(instance, expected);
            if (!Arrays.equals(expected, captured[0])
                    || fixture.getField("calls").getInt(instance) != 1)
                throw new AssertionError("Boxed gate argument slots/types");
            allow[0] = false;
            submit.invoke(instance, expected);
            if (fixture.getField("calls").getInt(instance) != 1)
                throw new AssertionError("Cancelled rendering ran original method");
            active[0] = false;
            captured[0] = null;
            submit.invoke(instance, expected);
            if (fixture.getField("calls").getInt(instance) != 2 || captured[0] != null)
                throw new AssertionError("Disabled hook must fall through");
        } finally {
            AgentBridge.install(previous);
        }
    }
}
