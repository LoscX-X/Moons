package com.blanoir.moons.client.module.impl.combat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded launch/teleport/fall transaction, independent of the live client. */
final class AutoMaceCycle {
    enum Phase {
        IDLE,
        AIM,
        WAIT_TELEPORT,
        FALL
    }

    private Phase phase = Phase.IDLE;
    private int ticks;
    private boolean positionReply;
    private Vec3 origin;
    private Vec3 impact;

    Phase phase() {
        return phase;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    void begin() {
        reset();
        phase = Phase.AIM;
    }

    void launched(Vec3 origin, Vec3 impact) {
        this.origin = origin;
        this.impact = impact;
        ticks = 0;
        positionReply = false;
        phase = Phase.WAIT_TELEPORT;
    }

    void positionReply() {
        if (phase == Phase.WAIT_TELEPORT) positionReply = true;
    }

    boolean confirmTeleport(Vec3 position) {
        if (phase != Phase.WAIT_TELEPORT || !positionReply || origin == null) return false;
        positionReply = false;
        // A ground correction, another teleport, or an ordinary jump is not this combo.
        if (position.y < origin.y + 1.5 || position.distanceToSqr(impact) > 2.25) return false;
        phase = Phase.FALL;
        ticks = 0;
        return true;
    }

    boolean expired() {
        return active()
                && ++ticks
                        > switch (phase) {
                            case AIM -> 20;
                            case WAIT_TELEPORT -> 40;
                            case FALL -> 60;
                            default -> 0;
                        };
    }

    void reset() {
        phase = Phase.IDLE;
        ticks = 0;
        positionReply = false;
        origin = null;
        impact = null;
    }

    /**
     * Vanilla 26.x: wind moves first, then pearl applies gravity/inertia before its ray.
     * Both are thrown at speed 1.5. Sending the pearl a tick later cannot catch the wind.
     * Wind's 0.3125 box is offset -0.15 vertically. ProjectileUtil.computeMargin is
     * zero for a fresh pearl (the 0.3 margin only develops as the projectile ages).
     */
    static Vec3 firstImpact(Vec3 eye, Vec3 inheritedMotion, Vec3 direction, int delay) {
        Vec3 windVelocity = direction.normalize().scale(1.5).add(inheritedMotion);
        Vec3 wind = eye.add(windVelocity.scale(delay + 1));
        AABB windBox =
                new AABB(
                        wind.x - .15625,
                        wind.y - .15,
                        wind.z - .15625,
                        wind.x + .15625,
                        wind.y + .1625,
                        wind.z + .15625);
        Vec3 pearl = eye.add(0, -.1, 0);
        Vec3 velocity = windVelocity.add(0, -.03, 0).scale((double) .99F);
        return windBox.clip(pearl, pearl.add(velocity)).orElse(null);
    }
}
