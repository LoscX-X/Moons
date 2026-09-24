package com.blanoir.moons.client.render.world;

import com.blanoir.moons.client.render.WorldOverlayRenderer.ColoredBox;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;

/** Regressions for bounded, independent edge geometry and conservative screen culling. */
public final class OverlayGeometryVerification {
    public static void main(String[] args) {
        var vertices = new ArrayList<Vector3f>();
        VertexConsumer recorder =
                (VertexConsumer)
                        Proxy.newProxyInstance(
                                VertexConsumer.class.getClassLoader(),
                                new Class<?>[] {VertexConsumer.class},
                                (proxy, method, values) -> {
                                    if (method.isDefault())
                                        return InvocationHandler.invokeDefault(
                                                proxy, method, values);
                                    if (method.getName().equals("addVertex"))
                                        vertices.add(
                                                new Vector3f(
                                                        (float) values[0],
                                                        (float) values[1],
                                                        (float) values[2]));
                                    return proxy;
                                });
        var identity = new Matrix4f();
        var box = new ColoredBox(0, 0, 0, 1, 1, 1, .2f, .8f, 1, .35f);
        OverlayGeometry.renderOutlineBox(identity, recorder, box);
        check(vertices.size() == 24, "Two vertices per edge, not double-sided ribbons");
        var edges = new HashSet<String>();
        for (int i = 0; i < vertices.size(); i += 2) {
            Vector3f a = vertices.get(i), b = vertices.get(i + 1);
            check(a.distanceSquared(b) == 1, "Each pair is one cube edge, never a diagonal");
            int start = (int) (a.x + 2 * a.y + 4 * a.z);
            int end = (int) (b.x + 2 * b.y + 4 * b.z);
            edges.add(Math.min(start, end) + ":" + Math.max(start, end));
        }
        check(edges.size() == 12, "All twelve edges appear exactly once");
        OverlayGeometry.renderSoftFill(identity, recorder, box);
        check(vertices.size() == 48, "Total budget is 48 vertices, formerly 120");
        var distant = new ColoredBox(200, -10, -200, 201, -8, -199, 1, 0, 0, .35f);
        for (float angle : new float[] {0, .7f, 1.57f, 3.14f}) {
            vertices.clear();
            OverlayGeometry.renderOutlineBox(new Matrix4f().rotateY(angle), recorder, distant);
            check(vertices.size() == 24, "Rotation cannot change edge topology");
            for (int i = 0; i < vertices.size(); i += 2) {
                float length = vertices.get(i).distance(vertices.get(i + 1));
                check(
                        Math.abs(length - 1) < .001 || Math.abs(length - 2) < .001,
                        "Rigid edges cannot expand or twist");
            }
        }
        var view = new OverlayFrustum();
        var perspective = new Matrix4f().perspective((float) Math.toRadians(90), 1, .05f, 1024);
        view.set(perspective, Vec3.ZERO);
        check(view.isVisible(-.5, -.5, -10, .5, .5, -9), "Visible target");
        check(view.isVisible(-.5, -.5, -200, .5, .5, -199), "Distant target retained");
        check(!view.isVisible(-.5, -.5, 10, .5, .5, 11), "Behind-camera target skipped");
        check(!view.isVisible(30, 0, -10, 31, 1, -9), "Off-screen target skipped");
        check(view.isVisible(-1, -1, -1, 1, 1, 1), "Camera-plane crossing not culled");
        check(view.isVisible(9.9, -1, -10, 11, 1, -9), "Partial intersection retained");
        view.set(perspective, new Vec3(1000, -50, -2000));
        check(
                view.isVisible(999.5, -50.5, -2010, 1000.5, -49.5, -2009),
                "Camera-relative coordinates");
        System.out.println(
                "Overlay checks passed: 12 independent edges, 48 vertices/box, view clipping.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
