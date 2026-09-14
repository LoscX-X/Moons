package com.blanoir.moons.ysm;

/** Frame motion in seconds. Callers supply interpolated render positions, not tick positions. */
public final class YsmMotionTracker {
    public record Frame(double seconds, double x, double y, double z, double yaw) {
        public double groundSpeed() {
            return seconds > 0 ? Math.hypot(x, z) / seconds : 0;
        }

        public double verticalSpeed() {
            return seconds > 0 ? y / seconds : 0;
        }

        public double yawSpeed() {
            return seconds > 0 ? yaw / seconds : 0;
        }
    }

    private Object entity;
    private double age = Double.NaN, x, y, z, yaw;
    private Frame frame = new Frame(0, 0, 0, 0, 0);

    public Frame sample(Object entity, double ticks, double x, double y, double z, double yaw) {
        if (!Double.isFinite(ticks + x + y + z + yaw)) return new Frame(0, 0, 0, 0, 0);
        if (this.entity == entity && ticks == age) return frame;
        double seconds = this.entity == entity && ticks > age ? (ticks - age) / 20 : 0;
        double angle = (yaw - this.yaw) % 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        frame =
                seconds > 0
                        ? new Frame(seconds, x - this.x, y - this.y, z - this.z, angle)
                        : new Frame(0, 0, 0, 0, 0);
        this.entity = entity;
        age = ticks;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        return frame;
    }
}
