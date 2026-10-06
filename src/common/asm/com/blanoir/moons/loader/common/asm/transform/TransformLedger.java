package com.blanoir.moons.loader.common.asm.transform;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Stores attempt-scoped generation and JVM delivery receipts by loader identity. */
final class TransformLedger {
    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> failedHooks = ConcurrentHashMap.newKeySet();
    private final Map<ClassLoader, Map<String, TransformReceipt>> receipts =
            new IdentityHashMap<>();
    private String attempt = "verification";
    private long generation;

    record Attempt(String id, long generation) {}

    synchronized Attempt currentAttempt() {
        return new Attempt(attempt, generation);
    }

    synchronized void beginAttempt(String id) {
        attempt = id;
        generation++;
        receipts.clear();
        installedHooks.clear();
        failedHooks.clear();
    }

    synchronized void accepted(ClassLoader loader, String name, boolean success) {
        var classes = receipts.get(loader);
        if (classes == null) return;
        var receipt = classes.get(name);
        if (receipt != null) classes.put(name, receipt.accepted(success));
    }

    synchronized void deliveryFailed(ClassLoader loader, String name) {
        var classes = receipts.get(loader);
        var receipt = classes == null ? null : classes.get(name);
        if (receipt != null)
            receipt.methods().forEach(method -> installedHooks.remove(method.hook()));
        record(
                currentAttempt(),
                loader,
                name,
                List.of(),
                Set.of(name + ":native-delivery-failed"),
                false);
    }

    synchronized List<TransformReceipt> receipts(ClassLoader loader) {
        return List.copyOf(receipts.getOrDefault(loader, Map.of()).values());
    }

    synchronized void record(
            Attempt context,
            ClassLoader loader,
            String name,
            List<TransformReceipt.Method> methods,
            Set<String> failures,
            boolean generated) {
        if (context.generation() != generation) return;
        receipts.computeIfAbsent(loader, ignored -> new LinkedHashMap<>())
                .put(
                        name,
                        new TransformReceipt(
                                context.id(),
                                name,
                                methods,
                                failures,
                                generated,
                                TransformReceipt.Acceptance.UNKNOWN));
        failedHooks.addAll(failures);
        if (generated) methods.forEach(method -> installedHooks.add(method.hook()));
    }

    Set<String> installedHooks() {
        return Set.copyOf(installedHooks);
    }

    Set<String> failedHooks() {
        return Set.copyOf(failedHooks);
    }
}
