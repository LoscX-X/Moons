package com.blanoir.moons.client.lifecycle;

/** Runs every cleanup action even when an earlier resource fails to release. */
public final class CleanupSequence {
    private CleanupSequence() {}

    public static void run(String name, Runnable... actions) {
        IllegalStateException aggregate = null;
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (Throwable failure) {
                if (aggregate == null) aggregate = new IllegalStateException(name + " failed");
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) throw aggregate;
    }
}
