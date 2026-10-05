package com.blanoir.moons.agent.core;

import com.blanoir.moons.agent.core.hooks.HookDispatcher;
import com.blanoir.moons.api.Branding;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Retransformation-safe method-body hooks. No fields, methods or interfaces are added. */
final class MoonsTransformer {

    private final MappingService mappings;
    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> failedHooks = ConcurrentHashMap.newKeySet();
    private final Map<ClassLoader, Map<String, TransformReceipt>> receipts =
            new IdentityHashMap<>();
    private String attempt = "verification";
    private long generation;

    private record Attempt(String id, long generation) {}

    private synchronized Attempt currentAttempt() {
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

    synchronized String readiness(ClassLoader loader, boolean firstTick) {
        boolean tick = false;
        var unavailable = new java.util.TreeSet<String>();
        for (var receipt : receipts(loader)) {
            if (mappings.targetsForClass(receipt.className()).stream()
                    .noneMatch(TargetMethod::required)) continue;
            if (!receipt.generated()
                    || receipt.acceptance() == TransformReceipt.Acceptance.REJECTED)
                return "FAILED:transform:" + receipt.className();
            for (String failure : receipt.failures()) {
                if (failure.startsWith("optional.")) continue;
                if (runtimeHookMiss(receipt.className(), failure)) unavailable.add(failure);
                else return "FAILED:hook:" + failure;
            }
            boolean startup =
                    receipt.methods().stream()
                            .anyMatch(method -> method.requirement() == HookRequirement.STARTUP);
            if (receipt.acceptance() == TransformReceipt.Acceptance.UNKNOWN
                    && !(startup && firstTick))
                return "WAITING:jvm-acceptance:" + receipt.className();
            if (receipt.methods().stream()
                    .anyMatch(method -> method.requirement() == HookRequirement.STARTUP)) {
                // An executed first tick proves JVM definition of a naturally loaded tick class.
                tick |= receipt.acceptance() == TransformReceipt.Acceptance.ACCEPTED || firstTick;
            }
        }
        if (!tick) return "WAITING:client.tick";
        return "READY:startup-hooks"
                + (unavailable.isEmpty()
                        ? ""
                        : ";unavailable-hooks=" + String.join(",", unavailable));
    }

    /** Preserve legacy matching tolerance without concealing transformation or delivery errors. */
    private boolean runtimeHookMiss(String className, String failure) {
        return mappings.targetsForClass(className).stream()
                .anyMatch(
                        target ->
                                HookRequirement.of(target) != HookRequirement.STARTUP
                                        && (failure.equals(target.id() + ":load-point-not-found")
                                                || failure.equals(
                                                        target.id() + ":target-not-found")));
    }

    private synchronized void record(
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

    MoonsTransformer(MappingService mappings) {
        this.mappings = mappings;
    }

    boolean targets(String className) {
        return mappings.targetsClass(className);
    }

    boolean requiredClass(String className) {
        return mappings.targetsForClass(className).stream().anyMatch(TargetMethod::required);
    }

    String[] targetClassNames() {
        return mappings.targetClassNames();
    }

    Set<String> installedHooks() {
        return Set.copyOf(installedHooks);
    }

    Set<String> failedHooks() {
        return Set.copyOf(failedHooks);
    }

    byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) {
        if (className == null || !mappings.targetsClass(className)) return null;
        List<TargetMethod> targets = mappings.targetsForClass(className);
        Attempt context = currentAttempt();
        var installed = new ArrayList<TransformReceipt.Method>();
        var failures = new HashSet<String>();
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            reader.accept(node, 0);
            boolean changed = false;
            Set<String> matched = new HashSet<>();
            for (MethodNode method : node.methods) {
                for (TargetMethod target :
                        mappings.targetsForMethod(className, method.name, method.desc)) {
                    matched.add(target.id());
                    boolean hooked = HookDispatcher.contains(method, target);
                    if (!hooked) {
                        hooked = HookDispatcher.install(method, target);
                        changed |= hooked;
                    }
                    if (hooked)
                        installed.add(
                                new TransformReceipt.Method(
                                        target.id(),
                                        method.name,
                                        method.desc,
                                        HookRequirement.of(target)));
                    else failures.add(target.id() + ":load-point-not-found");
                }
            }
            for (TargetMethod target : targets) {
                if (!matched.contains(target.id())) failures.add(target.id() + ":target-not-found");
            }
            if (!changed) {
                record(context, loader, className, installed, failures, true);
                return null;
            }
            boolean recomputeFrames = HookDispatcher.requiresFrames(targets);
            ClassWriter writer =
                    recomputeFrames
                            ? new LoaderAwareClassWriter(
                                    reader,
                                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                                    loader)
                            : new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] output = writer.toByteArray();
            record(context, loader, className, installed, failures, true);
            return output;
        } catch (Throwable failure) {
            failures.add(className + ":" + failure.getClass().getSimpleName());
            record(context, loader, className, installed, failures, false);
            System.err.println(
                    Branding.prefix()
                            + " Skipping failed transformation for "
                            + className
                            + ": "
                            + failure);
            return null;
        }
    }
}
