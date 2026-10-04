package com.blanoir.moons.agent.core;

import java.util.List;
import java.util.Set;

/** A successful ASM write is distinct from JVM acceptance of a retransformation. */
public record TransformReceipt(
        String attempt,
        String className,
        List<Method> methods,
        Set<String> failures,
        boolean generated,
        Acceptance acceptance) {
    public enum Acceptance {
        UNKNOWN,
        ACCEPTED,
        REJECTED
    }

    public record Method(
            String hook, String name, String descriptor, HookRequirement requirement) {}

    public TransformReceipt {
        methods = List.copyOf(methods);
        failures = Set.copyOf(failures);
    }

    TransformReceipt accepted(boolean success) {
        return new TransformReceipt(
                attempt,
                className,
                methods,
                failures,
                generated,
                success ? Acceptance.ACCEPTED : Acceptance.REJECTED);
    }
}
