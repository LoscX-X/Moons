package com.blanoir.moons.client.render;

/** Reentrant capture guard shared by ghost models, player highlights and nameplate hooks. */
public final class VisualModelCapture {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private VisualModelCapture() {}

    public static boolean active() {
        return DEPTH.get() > 0;
    }

    public static void run(Runnable draw) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try {
            draw.run();
        } finally {
            if (previous == 0) DEPTH.remove();
            else DEPTH.set(previous);
        }
    }

    public static void beginFrame() {
        if (active()) throw new IllegalStateException("Model capture leaked across frame boundary");
    }
}
