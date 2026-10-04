package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.renderer.GlStateManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Restores Minecraft's cached state after native rendering inside a glPushAttrib scope. */
final class YsmGlStateCache implements AutoCloseable {
    private record Slot(Field field, Object owner) {}

    private static final List<Slot> SLOTS = discover();
    private final Object[] values = new Object[SLOTS.size()];

    private YsmGlStateCache() {
        try {
            for (int i = 0; i < values.length; i++)
                values[i] = SLOTS.get(i).field.get(SLOTS.get(i).owner);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    public static YsmGlStateCache capture() {
        return new YsmGlStateCache();
    }

    @Override
    public void close() {
        try {
            for (int i = 0; i < values.length; i++)
                SLOTS.get(i).field.set(SLOTS.get(i).owner, values[i]);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    private static List<Slot> discover() {
        List<Slot> slots = new ArrayList<>();
        try {
            collect(
                    GlStateManager.class,
                    null,
                    slots,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch (IllegalAccessException error) {
            throw new ExceptionInInitializerError(error);
        }
        return List.copyOf(slots);
    }

    private static void collect(Class<?> type, Object owner, List<Slot> slots, Set<Object> visited)
            throws IllegalAccessException {
        if (owner != null && !visited.add(owner)) return;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) != (owner == null)) continue;
            field.setAccessible(true);
            if (field.getType().isPrimitive()) {
                if (!Modifier.isFinal(field.getModifiers())) slots.add(new Slot(field, owner));
                continue;
            }
            Object value = field.get(owner);
            if (value instanceof Object[] array) {
                for (Object element : array)
                    if (element != null) collect(element.getClass(), element, slots, visited);
            } else if (value != null
                    && value.getClass().getName().startsWith(GlStateManager.class.getName() + "$"))
                collect(value.getClass(), value, slots, visited);
        }
    }
}
