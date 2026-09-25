package com.blanoir.moons.client.render;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Render-thread scope for extra model submissions that belong only to the display output. */
public final class VisualModelCapture {
    private static int depth;
    private static boolean requested;
    private static int outlineColor;

    private VisualModelCapture() {}

    public static void beginFrame() {
        requested = false;
    }

    public static boolean active() {
        return depth != 0;
    }

    public static boolean requested() {
        return requested;
    }

    public static int outlineColor() {
        return outlineColor;
    }

    public static void submit(
            SubmitNodeCollector collector,
            UnaryOperator<RenderType> material,
            Consumer<SubmitNodeCollector> submit) {
        submit(collector, 0, material, submit);
    }

    public static void submit(
            SubmitNodeCollector collector,
            int color,
            UnaryOperator<RenderType> material,
            Consumer<SubmitNodeCollector> submit) {
        int previousColor = outlineColor;
        outlineColor = color;
        requested = true;
        depth++;
        try {
            submit.accept((SubmitNodeCollector) wrap(collector, material, true));
        } finally {
            depth--;
            outlineColor = previousColor;
        }
    }

    // Only the extra model uses this view. The level's collector and other entities stay unchanged.
    // Forwarding the interface also preserves new submission forms without cloning game collectors.
    private static OrderedSubmitNodeCollector wrap(
            OrderedSubmitNodeCollector collector,
            UnaryOperator<RenderType> material,
            boolean orderedRoot) {
        Class<?> contract =
                orderedRoot ? SubmitNodeCollector.class : OrderedSubmitNodeCollector.class;
        return (OrderedSubmitNodeCollector)
                Proxy.newProxyInstance(
                        contract.getClassLoader(),
                        new Class<?>[] {contract},
                        (proxy, method, arguments) -> {
                            if (arguments != null) {
                                for (int i = 0; i < arguments.length; i++) {
                                    if (arguments[i] instanceof RenderType type)
                                        arguments[i] = material.apply(type);
                                }
                            }
                            try {
                                if (method.isDefault()) {
                                    // Default overloads can construct materials before calling
                                    // another
                                    // collector method; keep their receiver inside this isolated
                                    // view.
                                    return InvocationHandler.invokeDefault(
                                            proxy, method, arguments);
                                }
                                Object result = method.invoke(collector, arguments);
                                return result instanceof OrderedSubmitNodeCollector next
                                        ? wrap(next, material, false)
                                        : result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }
}
