package com.blanoir.moons.client.management.rotation;

/**
 * Exact-angle transaction shared by silent USE_ITEM producers.
 *
 * <p>The USE_ITEM packet and the following movement packet must contain the
 * same float pair. The lock therefore survives the Java use invocation and is
 * released only after a matching movement packet is actually emitted. If the
 * invocation emitted no USE_ITEM packet, it closes immediately.
 */
public final class RotationInteractionLock {
    private boolean locked;
    private boolean interactionPacketSent;
    private boolean invocationCompleted;
    private float yaw;
    private float pitch;

    public void begin(float requestedYaw, float requestedPitch) {
        if (!locked) {
            yaw = requestedYaw;
            pitch = requestedPitch;
            locked = true;
            interactionPacketSent = false;
            invocationCompleted = false;
        }
    }

    public boolean markInteractionPacket(float packetYaw, float packetPitch) {
        if (!matches(packetYaw, packetPitch)) return false;
        interactionPacketSent = true;
        return true;
    }

    public void finishInvocation() {
        if (!locked) return;
        invocationCompleted = true;
        if (!interactionPacketSent) clear();
    }

    public boolean confirmMovement(float sentYaw, float sentPitch) {
        if (!locked || !invocationCompleted || !interactionPacketSent
                || !matches(sentYaw, sentPitch)) return false;
        clear();
        return true;
    }

    public boolean locked() { return locked; }
    public boolean interactionPacketSent() { return interactionPacketSent; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public RotationPair pair() { return new RotationPair(yaw, pitch); }

    public void clear() {
        locked = false;
        interactionPacketSent = false;
        invocationCompleted = false;
        yaw = 0.0F;
        pitch = 0.0F;
    }

    private boolean matches(float otherYaw, float otherPitch) {
        return locked
                && Float.floatToIntBits(yaw) == Float.floatToIntBits(otherYaw)
                && Float.floatToIntBits(pitch) == Float.floatToIntBits(otherPitch);
    }

    public record RotationPair(float yaw, float pitch) { }
}
