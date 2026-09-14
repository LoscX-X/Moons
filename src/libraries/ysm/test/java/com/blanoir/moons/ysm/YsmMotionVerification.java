package com.blanoir.moons.ysm;

final class YsmMotionVerification {
    static void verify() {
        Object entity = new Object();
        for (int fps : new int[] {20, 60, 240, 540}) {
            var tracker = new YsmMotionTracker();
            tracker.sample(entity, 0, 0, 0, 0, 170);
            for (int i = 1; i <= fps * 2; i++) {
                double seconds = (double) i / fps;
                // Simulate the render interpolation between 20 Hz game tick positions.
                double tick = seconds * 20, lower = Math.floor(tick), partial = tick - lower;
                double x = (lower * .2) + ((lower + 1) * .2 - lower * .2) * partial;
                double yaw = (170 + seconds * 90 + 180) % 360 - 180;
                var frame = tracker.sample(entity, tick, x, seconds * 2, 0, yaw);
                if (Math.abs(frame.groundSpeed() - 4) > 1e-8
                        || Math.abs(frame.verticalSpeed() - 2) > 1e-8
                        || Math.abs(frame.yawSpeed() - 90) > 1e-7)
                    throw new AssertionError("Motion changed with FPS: " + fps + " " + frame);
                if (tracker.sample(entity, tick, x, seconds * 2, 0, yaw) != frame)
                    throw new AssertionError("Duplicate body/hand frame must reuse motion");
            }
            if (tracker.sample(new Object(), 0, 900, 100, 0, 0).groundSpeed() != 0)
                throw new AssertionError("New entity must reset old motion");
            if (tracker.sample(entity, -1, 100, 0, 0, 0).seconds() != 0)
                throw new AssertionError("Rewound time must reset motion");
        }
        System.out.println(
                "YSM_MOTION_VERIFIED fps=20+60+240+540 duplicate=stable reset=entity+time");
    }
}
