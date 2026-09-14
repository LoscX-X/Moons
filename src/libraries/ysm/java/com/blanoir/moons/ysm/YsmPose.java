package com.blanoir.moons.ysm;

/** Additive position (model pixels) and rotation (degrees), multiplicative scale. */
public record YsmPose(
        double x,
        double y,
        double z,
        double pitch,
        double yaw,
        double roll,
        double scaleX,
        double scaleY,
        double scaleZ,
        boolean hidden) {
    public static final YsmPose IDENTITY = new YsmPose(0, 0, 0, 0, 0, 0, 1, 1, 1, false);

    public YsmPose {
        for (double value : new double[] {x, y, z, pitch, yaw, roll, scaleX, scaleY, scaleZ})
            if (!Double.isFinite(value))
                throw new IllegalArgumentException("Pose values must be finite");
        if (Math.abs(x) > 256
                || Math.abs(y) > 256
                || Math.abs(z) > 256
                || Math.abs(pitch) > 360
                || Math.abs(yaw) > 360
                || Math.abs(roll) > 360
                || scaleX < 0
                || scaleY < 0
                || scaleZ < 0
                || scaleX > 10
                || scaleY > 10
                || scaleZ > 10)
            throw new IllegalArgumentException("Pose values are outside the supported range");
    }
}
