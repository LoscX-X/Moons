package com.blanoir.moons.agent.core;

import com.blanoir.moons.agent.core.hooks.YsmHooks;
import com.blanoir.moons.api.bridge.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

/** Executes the version-specific audio return gate with replacement and vanilla fallthrough. */
final class YsmAudioHookVerification implements Opcodes {
    static void verify() throws Exception {
        String name = "com/blanoir/moons/agent/core/AudioGateFixture";
        ClassNode node = new ClassNode();
        node.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var constructor = node.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 1);
        constructor.visitEnd();
        var method =
                new MethodNode(
                        ACC_PUBLIC,
                        "getStream",
                        "(Ljava/lang/Object;Z)Ljava/util/concurrent/CompletableFuture;",
                        null,
                        null);
        method.visitLdcInsn("vanilla");
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/util/concurrent/CompletableFuture",
                "completedFuture",
                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;",
                false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 3);
        method.visitEnd();
        node.methods.add(method);
        if (!YsmHooks.audioStream(method, "audio.ysm-stream"))
            throw new AssertionError("YSM audio hook was not installed");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> fixture =
                new ClassLoader(YsmAudioHookVerification.class.getClassLoader()) {
                    Class<?> define() {
                        return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
                    }
                }.define();
        Object instance = fixture.getConstructor().newInstance();
        var call = fixture.getMethod("getStream", Object.class, boolean.class);
        Object[] replacement = {CompletableFuture.completedFuture("local")};
        RuntimeBridge bridge =
                (RuntimeBridge)
                        Proxy.newProxyInstance(
                                RuntimeBridge.class.getClassLoader(),
                                new Class<?>[] {RuntimeBridge.class},
                                (proxy, invoked, args) -> {
                                    if (invoked.getName().equals("onObjectValue")) {
                                        Object[] values = (Object[]) args[2];
                                        if (!values[0].equals("model.ogg")
                                                || !Boolean.TRUE.equals(values[1])
                                                || args[3] != null)
                                            throw new AssertionError(
                                                    "Audio arguments were not preserved");
                                        return replacement[0];
                                    }
                                    return null;
                                });
        RuntimeBridge previous = AgentBridge.install(bridge);
        try {
            if (call.invoke(instance, "model.ogg", true) != replacement[0])
                throw new AssertionError("Local stream replacement");
            replacement[0] = null;
            if (!((CompletableFuture<?>) call.invoke(instance, "model.ogg", true))
                    .join()
                    .equals("vanilla"))
                throw new AssertionError("Unclaimed sound must execute vanilla loader");
        } finally {
            AgentBridge.install(previous);
        }
    }
}
