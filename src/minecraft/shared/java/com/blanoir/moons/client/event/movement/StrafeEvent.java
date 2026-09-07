package com.blanoir.moons.client.event.movement;

/**
 * Mutable movement-input event fired immediately before vanilla converts
 * strafe/forward input into horizontal velocity.
 *
 * <p>Movement listeners can change all three arguments passed to
 * moveRelative.</p>
 */
public final class StrafeEvent {
    private float strafe;
    private float forward;
    private float friction;

    public StrafeEvent(float strafe, float forward, float friction) {
        this.strafe = strafe;
        this.forward = forward;
        this.friction = friction;
    }

    public float getStrafe() {
        return strafe;
    }

    public float getForward() {
        return forward;
    }

    public float getFriction() {
        return friction;
    }

    public void setStrafe(float value) {
        strafe = value;
    }

    public void setForward(float value) {
        forward = value;
    }

    public void setFriction(float value) {
        friction = value;
    }
}
