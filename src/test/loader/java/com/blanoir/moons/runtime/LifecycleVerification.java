package com.blanoir.moons.runtime;

import com.blanoir.moons.runtime.event.EventChannel;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable lifecycle assertions used by the Gradle check task. */
public final class LifecycleVerification {
    private LifecycleVerification() {}

    public static void main(String[] arguments) {
        verifySubscriptions();
        verifyFilteredSubscriptions();
        verifySnapshotDuringUnsubscribe();
        verifyFilterFailureIsolation();
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

    private static void verifyFilteredSubscriptions() {
        EventChannel<Integer> channel = new EventChannel<>();
        AtomicBoolean enabled = new AtomicBoolean();
        AtomicInteger filtered = new AtomicInteger();
        AtomicInteger external = new AtomicInteger();
        DefaultResourceScope scope = new DefaultResourceScope();
        scope.own(channel.subscribe(key -> enabled.get(), filtered::addAndGet));
        require(!channel.hasListeners("draw"), "Disabled subscription must not request a hook");
        channel.publish("draw", 1);
        require(filtered.get() == 0, "Disabled listener must not receive keyed events");

        var observer = channel.subscribe(external::addAndGet);
        require(channel.hasListeners("draw"), "Unfiltered observers must keep hooks active");
        channel.publish("draw", 2);
        require(filtered.get() == 0 && external.get() == 2, "Observer must bypass feature filter");
        enabled.set(true);
        channel.publish("draw", 3);
        require(filtered.get() == 3 && external.get() == 5, "Toggle must take effect immediately");

        observer.close();
        scope.close();
        scope.close();
        require(
                channel.listenerCount() == 0 && !channel.hasListeners("draw"),
                "Scope must remove both listener and its filter");
    }

    private static void verifySnapshotDuringUnsubscribe() {
        EventChannel<Integer> channel = new EventChannel<>();
        List<Integer> calls = new ArrayList<>();
        com.blanoir.moons.api.Subscription[] later = new com.blanoir.moons.api.Subscription[1];
        channel.subscribe(
                value -> {
                    calls.add(1);
                    later[0].close();
                });
        later[0] = channel.subscribe(value -> calls.add(2));
        channel.publish(0);
        channel.publish(0);
        require(
                calls.equals(List.of(1, 2, 1)),
                "Unsubscribe must preserve the current snapshot and affect the next dispatch");
    }

    private static void verifyFilterFailureIsolation() {
        EventChannel<Integer> channel = new EventChannel<>();
        AtomicInteger total = new AtomicInteger();
        channel.subscribe(
                key -> {
                    throw new IllegalStateException("expected filter failure");
                },
                value -> {
                    throw new AssertionError("Broken filter must not dispatch its listener");
                });
        channel.subscribe(total::addAndGet);
        require(channel.hasListeners("draw"), "Filter failures must defer to isolated dispatch");
        channel.publish("draw", 7);
        require(total.get() == 7, "Broken filter must not prevent later listeners from running");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
