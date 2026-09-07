package com.blanoir.moons.features;

/** Focused regression scenarios for extracted render interpolation. */
public final class RenderRotationVerification {
    private RenderRotationVerification() {}

    public static void main(String[] args) {
        fullLockCrossesYawBoundary();
        fullLockAdvancesFromPreviousTickTarget();
        bodyTurnStopsAtTarget();
        System.out.println("RENDER_ROTATION_VERIFIED");
    }

    private static void fullLockCrossesYawBoundary() {
        var state = new RenderRotationController.BodyRotationState();
        RenderRotationController.updateFullLockRenderRotation(
                state, -179.0F, 40.0F, 179.0F, 20.0F, 10, 0.5F);
        near(180.0F, state.fullLockRenderYaw, "head takes the short turn across 180");
        near(30.0F, state.fullLockRenderPitch, "pitch interpolates from vanilla state");
    }

    private static void fullLockAdvancesFromPreviousTickTarget() {
        var state = new RenderRotationController.BodyRotationState();
        RenderRotationController.updateFullLockRenderRotation(
                state, 40.0F, 20.0F, 0.0F, 0.0F, 10, 0.25F);
        RenderRotationController.updateFullLockRenderRotation(
                state, 60.0F, 40.0F, -90.0F, -90.0F, 10, 0.5F);
        near(30.0F, state.fullLockRenderYaw, "same tick retains its interpolation origin");
        RenderRotationController.updateFullLockRenderRotation(
                state, 80.0F, 60.0F, -90.0F, -90.0F, 11, 0.5F);
        near(70.0F, state.fullLockRenderYaw, "next tick starts from the previous target");
        near(50.0F, state.fullLockRenderPitch, "pitch target advances with the tick");
    }

    private static void bodyTurnStopsAtTarget() {
        var state = new RenderRotationController.BodyRotationState();
        state.bodyYaw = 179.0F;
        state.velocity = 400.0F;
        RenderRotationController.stepBodyYaw(state, -179.0F, 0.1F);
        near(181.0F, state.bodyYaw, "body stays in a continuous yaw domain without overshoot");
        near(0.0F, state.velocity, "reaching the target consumes angular velocity");
    }

    private static void near(float expected, float actual, String message) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 0.0001F) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
