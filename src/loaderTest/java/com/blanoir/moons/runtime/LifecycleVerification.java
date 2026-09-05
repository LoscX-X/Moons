package com.blanoir.moons.runtime;

import com.blanoir.moons.runtime.event.EventChannel;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable lifecycle assertions used by the Gradle check task. */
public final class LifecycleVerification {
    private LifecycleVerification() { }

    public static void main(String[] arguments) {
        verifySubscriptions();
        verifyResourceScope();
        System.out.println("MOONS_RUNTIME_LIFECYCLE_VERIFIED");
    }

    private static void verifySubscriptions() {
        EventChannel<Integer> channel = new EventChannel<>();
        AtomicInteger total = new AtomicInteger();
        var subscription = channel.subscribe(total::addAndGet);
        channel.publish(2);
        subscription.close();
        subscription.close();
        channel.publish(4);
        if (total.get() != 2 || channel.listenerCount() != 0) {
            throw new AssertionError("Subscription was not removed deterministically");
        }
    }

    private static void verifyResourceScope() {
        DefaultResourceScope scope = new DefaultResourceScope();
        List<Integer> closed = new ArrayList<>();
        scope.own(() -> closed.add(1));
        scope.own(() -> closed.add(2));
        scope.close();
        scope.close();
        if (!scope.isClosed() || !closed.equals(List.of(2, 1))) {
            throw new AssertionError("Resources were not closed once in reverse order: " + closed);
        }
        AtomicInteger lateClose = new AtomicInteger();
        try {
            scope.own(lateClose::incrementAndGet);
            throw new AssertionError("Closed scope accepted a resource");
        } catch (IllegalStateException expected) {
            if (lateClose.get() != 1) {
                throw new AssertionError("Rejected resource was not closed");
            }
        }
    }
}
