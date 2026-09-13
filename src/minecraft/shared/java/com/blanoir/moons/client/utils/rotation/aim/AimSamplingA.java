package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.math.RandomMath;

/** A: Existing bounded random variation and packet-envelope sampling only.
 * Random draws remain in the original caller order; no sampling cache or sent-angle history is owned here. */
public final class AimSamplingA {
    /** Packet pitch acceleration limit shared by the supported client versions. */
    private static final double MATRIX_MAX_PACKET_PITCH_ACCELERATION = 2.10D;

    private AimSamplingA() {}

    /** Samples an existing bounded aim variation without owning any profile state. */
    public static double sampleVariation(double minimum, double maximum) {
        return RandomMath.between(minimum, maximum);
    }

    /** Samples one packet's acceleration envelope and bounded angular error. */
    public static PacketMotion samplePacketMotion(boolean lockMode, double urgency) {
        double yawAcceleration =
                lockMode
                        ? RandomMath.between(11.0D + urgency * 4.0D, 16.0D + urgency * 7.0D)
                        : RandomMath.between(6.0D + urgency * 2.0D, 9.0D + urgency * 3.0D);
        // These are deliberately packet-domain limits. Do not compensate for a
        // slow render-domain target tracker by increasing them; adjust vertical
        // prediction/stabilisation while preserving the acceleration limit.
        double pitchAcceleration =
                lockMode
                        ? RandomMath.between(1.45D + urgency * 0.15D, 1.85D + urgency * 0.25D)
                        : RandomMath.between(1.05D + urgency * 0.15D, 1.45D + urgency * 0.20D);
        pitchAcceleration = Math.min(pitchAcceleration, MATRIX_MAX_PACKET_PITCH_ACCELERATION);
        double yawError = RandomMath.between(lockMode ? -0.12D : -0.18D, lockMode ? 0.12D : 0.18D);
        double pitchError = RandomMath.between(-0.045D, 0.045D);
        return new PacketMotion(yawAcceleration, pitchAcceleration, yawError, pitchError);
    }

    /**
     * Generic profile used only when the compatibility option is off.
     * Keep this separate so generic responsiveness can never silently weaken
     * the stricter packet-acceleration limit.
     */
    public static PacketMotion sampleResponsivePacketMotion(boolean lockMode, double urgency) {
        double yawAcceleration =
                lockMode
                        ? RandomMath.between(20.0D + urgency * 7.0D, 29.0D + urgency * 10.0D)
                        : RandomMath.between(12.0D + urgency * 4.0D, 18.0D + urgency * 7.0D);
        double pitchAcceleration =
                lockMode
                        ? RandomMath.between(3.2D + urgency * 0.8D, 4.6D + urgency * 1.1D)
                        : RandomMath.between(2.2D + urgency * 0.5D, 3.3D + urgency * 0.8D);
        double yawError = RandomMath.between(lockMode ? -0.10D : -0.16D, lockMode ? 0.10D : 0.16D);
        double pitchError = RandomMath.between(-0.04D, 0.04D);
        return new PacketMotion(yawAcceleration, pitchAcceleration, yawError, pitchError);
    }

    public record PacketMotion(
            double yawAcceleration, double pitchAcceleration, double yawError, double pitchError) {}
}
