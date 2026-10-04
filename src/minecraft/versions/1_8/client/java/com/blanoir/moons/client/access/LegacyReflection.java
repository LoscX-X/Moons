package com.blanoir.moons.client.access;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Private access names generated from the same verified mappings as the payload. */
public final class LegacyReflection {
    private static final Properties NAMES = new Properties();
    private static final Map<String, AccessibleObject> CACHE = new ConcurrentHashMap<>();

    static {
        try (var in = LegacyReflection.class.getResourceAsStream("/moons/1_8/members.properties")) {
            if (in == null)
                throw new IllegalStateException("Missing 1.8.9 private member mappings");
            NAMES.load(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private LegacyReflection() {}

    public static Field field(Class<?> type, String owner, String name) {
        return (Field)
                CACHE.computeIfAbsent(
                        owner + "#" + name,
                        k -> {
                            for (String candidate :
                                    new String[] {name, NAMES.getProperty(k, name)}) {
                                try {
                                    Field f = type.getDeclaredField(candidate);
                                    f.setAccessible(true);
                                    return f;
                                } catch (NoSuchFieldException ignored) {
                                }
                            }
                            throw new IllegalStateException("Missing field " + k);
                        });
    }

    public static Method method(Class<?> type, String owner, String name, Class<?>... params) {
        return (Method)
                CACHE.computeIfAbsent(
                        owner + "#" + name + Arrays.toString(params),
                        k -> {
                            for (String candidate :
                                    new String[] {
                                        name, NAMES.getProperty(owner + "#" + name, name)
                                    }) {
                                try {
                                    Method m = type.getDeclaredMethod(candidate, params);
                                    m.setAccessible(true);
                                    return m;
                                } catch (NoSuchMethodException ignored) {
                                }
                            }
                            throw new IllegalStateException("Missing method " + k);
                        });
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object target, Class<?> type, String owner, String name) {
        try {
            return (T) field(type, owner, name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void set(Object target, Class<?> type, String owner, String name, Object value) {
        try {
            field(type, owner, name).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Object invoke(Object target, Class<?> type, String owner, String name) {
        try {
            return method(type, owner, name).invoke(target);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error r) throw r;
            throw new IllegalStateException(t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
