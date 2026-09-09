package com.blanoir.moons.agent.core;

import com.blanoir.moons.api.bridge.RuntimeBridge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;

/** Exercises primitive and object calls across genuinely distinct API class loaders. */
final class RuntimeBridgeAdapterVerification {
    static void verify() throws Exception {
        byte[] generated = RuntimeBridgeAdapter.classBytes();
        ClassNode node = new ClassNode();
        new ClassReader(generated).accept(node, 0);
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction.getOpcode() == Opcodes.ANEWARRAY
                        || instruction.getOpcode() == Opcodes.NEW) {
                    throw new AssertionError("Bridge forwarding allocated on the call path");
                }
            }
        }
        ClassLoader loader =
                new ClassLoader(RuntimeBridge.class.getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.startsWith("com.blanoir.moons.api.bridge."))
                            return super.loadClass(name, resolve);
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> found = findLoadedClass(name);
                            if (found == null) {
                                byte[] bytes;
                                if (name.equals(RuntimeBridgeAdapter.NAME)) bytes = generated;
                                else
                                    try (InputStream source =
                                            RuntimeBridge.class.getResourceAsStream(
                                                    "/" + name.replace('.', '/') + ".class")) {
                                        if (source == null) throw new ClassNotFoundException(name);
                                        bytes = source.readAllBytes();
                                    } catch (java.io.IOException failure) {
                                        throw new ClassNotFoundException(name, failure);
                                    }
                                found = defineClass(name, bytes, 0, bytes.length);
                            }
                            if (resolve) resolveClass(found);
                            return found;
                        }
                    }
                };
        Object marker = new Object();
        IllegalStateException failure = new IllegalStateException("expected bridge failure");
        int[] calls = {0};
        RuntimeBridge delegate =
                new RuntimeBridge() {
                    @Override
                    public boolean onKey(Object handler, long window, int action, Object event) {
                        if (handler != marker
                                || window != 0x123456789ABCDEFL
                                || action != 2
                                || event != marker)
                            throw new AssertionError(
                                    "Key arguments changed across the loader boundary");
                        calls[0]++;
                        return false;
                    }

                    @Override
                    public float onFloatValue(
                            String id, Object owner, float argument, float value) {
                        if (!id.equals("float")
                                || owner != marker
                                || argument != 1.5F
                                || value != 2.5F)
                            throw new AssertionError(
                                    "Float arguments changed across the loader boundary");
                        return -3.25F;
                    }

                    @Override
                    public Object onObjectValue(
                            String id, Object owner, Object argument, Object value) {
                        return marker;
                    }

                    @Override
                    public boolean isHookActive(String id) {
                        return id.equals("active");
                    }

                    @Override
                    public void close() {
                        throw failure;
                    }
                };
        Class<?> adapterType = loader.loadClass(RuntimeBridgeAdapter.NAME);
        Object adapter =
                adapterType
                        .getConstructor(MethodHandle[].class)
                        .newInstance((Object) RuntimeBridgeAdapter.handles(delegate));
        if (RuntimeBridge.class.isInstance(adapter))
            throw new AssertionError("Test did not isolate API loaders");
        Object key =
                adapterType
                        .getMethod("onKey", Object.class, long.class, int.class, Object.class)
                        .invoke(adapter, marker, 0x123456789ABCDEFL, 2, marker);
        if (!Boolean.FALSE.equals(key) || calls[0] != 1)
            throw new AssertionError("Key cancellation was lost");
        Object value =
                adapterType
                        .getMethod(
                                "onFloatValue",
                                String.class,
                                Object.class,
                                float.class,
                                float.class)
                        .invoke(adapter, "float", marker, 1.5F, 2.5F);
        if (!Float.valueOf(-3.25F).equals(value)) throw new AssertionError("Float result was lost");
        if (adapterType
                        .getMethod(
                                "onObjectValue",
                                String.class,
                                Object.class,
                                Object.class,
                                Object.class)
                        .invoke(adapter, "object", marker, marker, null)
                != marker) throw new AssertionError("Object identity changed");
        if (!Boolean.TRUE.equals(
                adapterType.getMethod("isHookActive", String.class).invoke(adapter, "active")))
            throw new AssertionError("Active hook was skipped");
        if (!Boolean.FALSE.equals(
                adapterType.getMethod("isHookActive", String.class).invoke(adapter, "inactive")))
            throw new AssertionError("Disabled hook was active");
        adapterType
                .getMethod("onWorldRender", Object.class, Object.class, Object.class, int.class)
                .invoke(adapter, marker, marker, marker, 1);
        adapterType
                .getMethod("onMouseScroll", Object.class, long.class, double.class, double.class)
                .invoke(adapter, marker, 42L, 0.5D, -0.5D);
        try {
            adapterType.getMethod("close").invoke(adapter);
            throw new AssertionError("Delegate failure was swallowed");
        } catch (InvocationTargetException expected) {
            if (expected.getCause() != failure)
                throw new AssertionError("Delegate failure was wrapped", expected);
        }
        System.out.println("LOADER_LOCAL_TYPED_BRIDGE_VERIFIED");
    }
}
