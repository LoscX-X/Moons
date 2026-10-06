package com.blanoir.moons.ysm.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Compare packed submission with Minecraft's original position and normal helpers. */
final class YsmVerticesVerification {
    static void verify() {
        var pose = new PoseStack();
        pose.translate(.3, -2, 7);
        pose.mulPose(new Matrix4f().rotationXYZ(.2f, -.6f, .1f));
        for (float[] scale : new float[][] {{1, 1, 1}, {-1, -1, 1}, {.7f, 2, .4f}}) {
            pose.pushPose();
            pose.scale(scale[0], scale[1], scale[2]);
            float[] vertices = {
                1, 2, 3, .2f, .8f, 0, 1, 0, 2, 2, 3, .7f, .8f, 0, 1, 0, 2, 2, 4, .7f, .1f, 0, 1, 0,
                1, 2, 4, .2f, .1f, 0, 1, 0, -3, 0, 1, .2f, .8f, .6f, 0, .8f, -2.2f, 0, .4f, .7f,
                .8f, .6f, 0, .8f, -2.2f, 1, .4f, .7f, .1f, .6f, 0, .8f, -3, 1, 1, .2f, .1f, .6f, 0,
                .8f
            };
            var expected = new Capture();
            var actual = new Capture();
            for (int i = 0; i < vertices.length; i += 8)
                expected.consumer
                        .addVertex(
                                pose.last().pose(), vertices[i], vertices[i + 1], vertices[i + 2])
                        .setColor(-1)
                        .setUv(vertices[i + 3], vertices[i + 4])
                        .setOverlay(0x12345678)
                        .setLight(0x00f00070)
                        .setNormal(pose.last(), vertices[i + 5], vertices[i + 6], vertices[i + 7]);
            YsmVertices.emit(pose.last(), actual.consumer, vertices, 0x12345678, 0x00f00070);
            if (expected.values.size() != actual.values.size())
                throw new AssertionError("Vertex count");
            for (int i = 0; i < expected.values.size(); i++) {
                Object[] a = expected.values.get(i), b = actual.values.get(i);
                for (int j = 0; j < a.length; j++) {
                    if (a[j] instanceof Float f && b[j] instanceof Float g) {
                        if (Math.abs(f - g) > 1e-6f)
                            throw new AssertionError("Transformed vertex component " + j);
                    } else if (!a[j].equals(b[j]))
                        throw new AssertionError("Packed vertex component " + j);
                }
            }
            pose.popPose();
        }
        System.out.println(
                "YSM_VERTICES_VERIFIED transforms=rotation+reflection+nonuniform-scale packed=equivalent");
    }

    private static final class Capture {
        final List<Object[]> values = new ArrayList<>();
        Object[] current;
        final VertexConsumer consumer =
                (VertexConsumer)
                        Proxy.newProxyInstance(
                                VertexConsumer.class.getClassLoader(),
                                new Class<?>[] {VertexConsumer.class},
                                (proxy, method, args) -> {
                                    String name = method.getName();
                                    if (name.equals("addVertex") && args.length == 11) {
                                        values.add(args.clone());
                                        return null;
                                    }
                                    if (method.isDefault())
                                        return InvocationHandler.invokeDefault(proxy, method, args);
                                    switch (name) {
                                        case "addVertex" -> {
                                            current = new Object[11];
                                            System.arraycopy(args, 0, current, 0, 3);
                                            values.add(current);
                                        }
                                        case "setColor" ->
                                                current[3] =
                                                        args.length == 1
                                                                ? args[0]
                                                                : ((int) args[3] << 24)
                                                                        | ((int) args[0] << 16)
                                                                        | ((int) args[1] << 8)
                                                                        | (int) args[2];
                                        case "setUv" -> {
                                            current[4] = args[0];
                                            current[5] = args[1];
                                        }
                                        case "setUv1" ->
                                                current[6] = (int) args[0] | ((int) args[1] << 16);
                                        case "setUv2" ->
                                                current[7] = (int) args[0] | ((int) args[1] << 16);
                                        case "setOverlay" -> current[6] = args[0];
                                        case "setLight" -> current[7] = args[0];
                                        case "setNormal" ->
                                                System.arraycopy(args, 0, current, 8, 3);
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected vertex call: " + method);
                                    }
                                    return proxy;
                                });
    }
}
