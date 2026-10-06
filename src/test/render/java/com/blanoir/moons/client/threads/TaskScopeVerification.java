package com.blanoir.moons.client.threads;

import com.blanoir.moons.client.lifecycle.ActivationGate;
import com.blanoir.moons.client.lifecycle.CleanupSequence;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses real workers to distinguish cancellation, delayed disposal and confirmed termination. */
public final class TaskScopeVerification {
    public static void main(String[] arguments) throws Exception {
        verifyCancellationAndCapacity();
        verifyOwnerAndStaleDispatch();
        verifyCompletionAndReentry();
        verifyActivationCancellation();
        verifyActivationGate();
        verifyCleanupFailures();
        verifySnapshotOwnership();
        System.out.println(
                "MOONS_TASK_SCOPE_VERIFIED capacity cancellation late-disposal stopping termination owner stale-dispatch reentry");
    }

    private static void verifySnapshotOwnership() throws Exception {
        Thread ownerThread = Thread.currentThread();
        var owner =
                new ThreadBoundExecutor(
                        "snapshot fixture",
                        () -> Thread.currentThread() == ownerThread,
                        Runnable::run);
        var tasks = new TaskScope("snapshot", Executors.newSingleThreadExecutor(), 4);
        var disposed = new AtomicInteger();
        try {
            int[] mutable = {7};
            var first =
                    new SnapshotJob<>(
                            owner,
                            tasks,
                            () -> mutable.clone(),
                            input -> {
                                if (Thread.currentThread() == ownerThread)
                                    throw new AssertionError("Computation ran on owner");
                                return input[0];
                            },
                            input -> true,
                            value -> disposed.incrementAndGet());
            mutable[0] = 99;
            tasks.submit(() -> 0).get(5, TimeUnit.SECONDS);
            if (first.take().orElseThrow() != 7 || first.take().isPresent())
                throw new AssertionError("Snapshot was not detached/transferred once");
            first.close();
            var stale =
                    new SnapshotJob<>(
                            owner,
                            tasks,
                            () -> 1,
                            input -> new Object(),
                            input -> true,
                            value -> disposed.incrementAndGet());
            tasks.submit(() -> 0).get(5, TimeUnit.SECONDS);
            tasks.invalidateResults();
            if (stale.take().isPresent() || disposed.get() != 1)
                throw new AssertionError("Stale snapshot was not disposed");
            stale.close();
            var abandoned =
                    new SnapshotJob<>(
                            owner,
                            tasks,
                            () -> 1,
                            input -> new Object(),
                            input -> true,
                            value -> disposed.incrementAndGet());
            tasks.submit(() -> 0).get(5, TimeUnit.SECONDS);
            abandoned.close();
            abandoned.close();
            if (disposed.get() != 2)
                throw new AssertionError("Unconsumed completed snapshot was not disposed once");
            var broken =
                    new SnapshotJob<>(
                            owner,
                            tasks,
                            () -> 1,
                            input -> new Object(),
                            input -> {
                                throw new IllegalStateException("validation failed");
                            },
                            value -> disposed.incrementAndGet());
            tasks.submit(() -> 0).get(5, TimeUnit.SECONDS);
            try {
                broken.take();
                throw new AssertionError("Validation failure was hidden");
            } catch (IllegalStateException expected) {
                if (disposed.get() != 3)
                    throw new AssertionError("Validation failure leaked result");
            }
        } finally {
            tasks.close();
            if (!tasks.awaitTermination(Duration.ofSeconds(5)))
                throw new AssertionError("Snapshot worker leaked");
        }
    }

    private static void verifyCleanupFailures() {
        var observed = new StringBuilder();
        try {
            CleanupSequence.run(
                    "fixture",
                    () -> {
                        observed.append('a');
                        throw new IllegalStateException("first");
                    },
                    () -> observed.append('b'),
                    () -> {
                        observed.append('c');
                        throw new AssertionError("last");
                    });
            throw new AssertionError("Cleanup failure was hidden");
        } catch (IllegalStateException failure) {
            if (!observed.toString().equals("abc") || failure.getSuppressed().length != 2)
                throw new AssertionError("Cleanup stopped before releasing all resources", failure);
        }
    }

    private static void verifyCancellationAndCapacity() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var disposed = new AtomicInteger();
        var queuedRan = new AtomicInteger();
        var scope = new TaskScope("fixture", Executors.newSingleThreadExecutor(), 2);
        try {
            var running =
                    scope.submit(
                            () -> {
                                entered.countDown();
                                // Deliberately ignores interruption, as native/IO work may do.
                                while (true) {
                                    try {
                                        if (!finish.await(5, TimeUnit.SECONDS))
                                            throw new AssertionError("Unreleased fixture");
                                        break;
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                                return new Object();
                            },
                            ignored -> disposed.incrementAndGet());
            await(entered);
            var queued = scope.submit(queuedRan::incrementAndGet);
            try {
                scope.submit(() -> 3);
                throw new AssertionError("Overload was accepted");
            } catch (RejectedExecutionException expected) {
            }
            var token = scope.token();
            scope.close();
            scope.close();
            if (token.valid() || !running.isCancelled() || !queued.isCancelled())
                throw new AssertionError("Stop did not invalidate/cancel work");
            if (scope.state() != TaskScope.State.STOPPING
                    || scope.awaitTermination(Duration.ofMillis(5)))
                throw new AssertionError("Cancellation was falsely reported as termination");
            try {
                scope.submit(() -> 4);
                throw new AssertionError("Closed scope accepted work");
            } catch (RejectedExecutionException expected) {
            }
            finish.countDown();
            if (!scope.awaitTermination(Duration.ofSeconds(5))
                    || scope.state() != TaskScope.State.TERMINATED)
                throw new AssertionError("Worker did not terminate");
            if (disposed.get() != 1 || queuedRan.get() != 0 || scope.pending() != 0)
                throw new AssertionError("Late result was leaked or disposed twice");
        } finally {
            finish.countDown();
            scope.close();
            scope.awaitTermination(Duration.ofSeconds(5));
        }
    }

    private static void verifyOwnerAndStaleDispatch() throws Exception {
        Thread owner = Thread.currentThread();
        var actions = new ArrayDeque<Runnable>();
        var domain =
                new ThreadBoundExecutor(
                        "fixture owner", () -> Thread.currentThread() == owner, actions::addLast);
        var calls = new AtomicInteger();
        var scope = new TaskScope("dispatch", Executors.newSingleThreadExecutor(), 4);
        try {
            var token = scope.token();
            domain.execute(token, calls::incrementAndGet);
            scope.invalidateResults();
            actions.removeFirst().run();
            domain.execute(scope.token(), calls::incrementAndGet);
            actions.removeFirst().run();
            domain.execute(scope.token(), calls::incrementAndGet);
            scope.close();
            actions.removeFirst().run();
            if (calls.get() != 1)
                throw new AssertionError("Old queued callback crossed a lifetime");
            var wrongThread = new AtomicInteger();
            Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    domain.requireOwner();
                                } catch (IllegalStateException expected) {
                                    wrongThread.incrementAndGet();
                                }
                            });
            worker.start();
            worker.join(5000);
            if (worker.isAlive() || wrongThread.get() != 1)
                throw new AssertionError("Wrong owner was not diagnosed");
        } finally {
            scope.close();
            scope.awaitTermination(Duration.ofSeconds(5));
        }
    }

    private static void verifyCompletionAndReentry() throws Exception {
        var scope = new TaskScope("reentry", Executors.newSingleThreadExecutor(), 4);
        try {
            int result =
                    scope.submit(() -> 7)
                            .thenCompose(value -> scope.submit(() -> value * 3))
                            .get(5, TimeUnit.SECONDS);
            if (result != 21) throw new AssertionError("Reentrant submission failed");
            boolean prevented =
                    scope.submit(
                                    () -> {
                                        try {
                                            scope.awaitTermination(Duration.ZERO);
                                            return false;
                                        } catch (IllegalStateException expected) {
                                            return true;
                                        } catch (InterruptedException failure) {
                                            throw new AssertionError(failure);
                                        }
                                    })
                            .get(5, TimeUnit.SECONDS);
            if (!prevented) throw new AssertionError("Worker could await itself");
        } finally {
            scope.close();
            if (!scope.awaitTermination(Duration.ofSeconds(5)))
                throw new AssertionError("Reentry worker leaked");
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
    }

    private static void verifyActivationCancellation() throws Exception {
        var scope = new TaskScope("activation", Executors.newSingleThreadExecutor(), 4);
        var entered = new CountDownLatch(1);
        try {
            var before = scope.token();
            var work =
                    scope.submit(
                            () -> {
                                entered.countDown();
                                try {
                                    new CountDownLatch(1).await();
                                } catch (InterruptedException cancelled) {
                                    Thread.currentThread().interrupt();
                                }
                                return 1;
                            });
            await(entered);
            scope.cancelOutstanding();
            if (before.valid() || !work.isCancelled() || scope.state() != TaskScope.State.ACCEPTING)
                throw new AssertionError("Activation stop shut down the reusable worker");
            var next = scope.token();
            if (!next.valid() || scope.submit(() -> 42).get(5, TimeUnit.SECONDS) != 42)
                throw new AssertionError("Next activation could not run");
        } finally {
            scope.close();
            if (!scope.awaitTermination(Duration.ofSeconds(5)))
                throw new AssertionError("Activation worker leaked");
        }
    }

    private static void verifyActivationGate() {
        var gate = new ActivationGate();
        var events = new com.blanoir.moons.runtime.event.EventChannel<String>();
        var observed = new StringBuilder();
        var first = events.subscribe(gate.guard(value -> observed.append("a")));
        var second =
                events.subscribe(
                        gate.guardFilter("accepted"::equals),
                        gate.guard(value -> observed.append("b")));
        events.publish("accepted", "event");
        if (!observed.isEmpty() || events.listenerCount() != 2)
            throw new AssertionError("Inactive gate ran work");
        gate.activate();
        events.publish("accepted", "event");
        gate.deactivate();
        events.publish("accepted", "event");
        gate.activate();
        events.publish("accepted", "event");
        if (!observed.toString().equals("abab") || events.listenerCount() != 2)
            throw new AssertionError("Gate reordered/duplicated listeners or ran disabled work");
        first.close();
        second.close();
        gate.deactivate();
        if (events.listenerCount() != 0) throw new AssertionError("Gate subscriptions leaked");
        var hooks = new com.blanoir.moons.runtime.event.EventChannel<String>();
        var subscription =
                hooks.subscribe(gate.guardFilter("accepted"::equals), gate.guard(value -> {}));
        if (hooks.hasListeners("accepted"))
            throw new AssertionError("Inactive hook advertised coverage");
        gate.activate();
        if (!hooks.hasListeners("accepted") || hooks.hasListeners("other"))
            throw new AssertionError("Hook filter changed its key semantics");
        subscription.close();
    }
}
