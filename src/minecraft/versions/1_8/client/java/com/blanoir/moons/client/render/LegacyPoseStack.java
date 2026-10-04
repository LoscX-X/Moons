package com.blanoir.moons.client.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayDeque;

/** CPU transform stack for Moons geometry, applied before the current game model-view matrix. */
public final class LegacyPoseStack {
    private final ArrayDeque<Matrix4f> stack = new ArrayDeque<>();

    public LegacyPoseStack() {
        stack.push(new Matrix4f());
    }

    public record Pose(Matrix4f pose) {}

    public Pose last() {
        return new Pose(stack.peek());
    }

    public void pushPose() {
        stack.push(new Matrix4f(stack.peek()));
    }

    public void popPose() {
        if (stack.size() <= 1) throw new IllegalStateException("Unbalanced pose stack");
        stack.pop();
    }

    public void translate(double x, double y, double z) {
        stack.peek().translate((float) x, (float) y, (float) z);
    }

    public void scale(float x, float y, float z) {
        stack.peek().scale(x, y, z);
    }

    public void mulPose(Quaternionf rotation) {
        stack.peek().rotate(rotation);
    }
}
