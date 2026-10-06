package com.blanoir.moons.loader.common.mapping;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One logical hook with a shared entry and exact, named runtime variants. */
public final class InjectionPoint {
    public enum Environment {
        VANILLA,
        FABRIC,
        QUILT,
        FORGE,
        NEOFORGE
    }

    private final Map<Environment, TargetMethod> variants;
    private final List<TargetMethod> baseline;

    public InjectionPoint(TargetMethod shared) {
        this(List.of(shared));
    }

    /** One hook may have several exact method boundaries and named/obfuscated aliases. */
    public InjectionPoint(List<TargetMethod> shared) {
        this(shared, Map.of(Environment.VANILLA, shared.getFirst()));
        for (TargetMethod target : baseline) {
            if (!id().equals(target.id()))
                throw new IllegalArgumentException("Baseline must retain hook ID " + id());
        }
    }

    private InjectionPoint(List<TargetMethod> baseline, Map<Environment, TargetMethod> variants) {
        this.baseline = List.copyOf(baseline);
        this.variants = Map.copyOf(variants);
    }

    public List<TargetMethod> allTargets() {
        java.util.ArrayList<TargetMethod> targets = new java.util.ArrayList<>(baseline);
        for (Environment environment : Environment.values()) {
            if (environment != Environment.VANILLA && variants.containsKey(environment))
                targets.add(variants.get(environment));
        }
        return List.copyOf(targets);
    }

    public String id() {
        return variants.get(Environment.VANILLA).id();
    }

    /** Environments without a known difference use the shared entry. */
    public TargetMethod variant(Environment environment) {
        Objects.requireNonNull(environment);
        return variants.getOrDefault(environment, variants.get(Environment.VANILLA));
    }

    public Map<Environment, TargetMethod> variants() {
        return variants;
    }

    public InjectionPoint withVariant(Environment environment, TargetMethod target) {
        Objects.requireNonNull(environment);
        if (environment == Environment.VANILLA || variants.containsKey(environment)) {
            throw new IllegalArgumentException("Duplicate injection environment: " + environment);
        }
        if (!id().equals(target.id())) {
            throw new IllegalArgumentException("Variant must retain hook ID " + id());
        }
        for (TargetMethod existing : allTargets()) {
            boolean overlaps =
                    existing.classNames().stream().anyMatch(target.classNames()::contains)
                            && existing.methodNames().stream()
                                    .anyMatch(target.methodNames()::contains)
                            && existing.descriptors().stream()
                                    .anyMatch(target.descriptors()::contains);
            if (overlaps
                    && (existing.hook() != target.hook()
                            || existing.installer() != target.installer())) {
                throw new IllegalArgumentException("Ambiguous injection variant for " + id());
            }
        }
        EnumMap<Environment, TargetMethod> next = new EnumMap<>(Environment.class);
        next.putAll(variants);
        next.put(environment, target);
        return new InjectionPoint(baseline, next);
    }

    /** A signature-only difference keeps the same hook algorithm and logical identity. */
    public InjectionPoint withDescriptors(Environment environment, List<String> descriptors) {
        if (baseline.size() != 1)
            throw new IllegalArgumentException(
                    "Multiple baseline targets require exact compatibility declarations");
        TargetMethod shared = variant(Environment.VANILLA);
        return withVariant(
                environment,
                new TargetMethod(
                        id(),
                        shared.classNames(),
                        shared.methodNames(),
                        descriptors,
                        shared.hook(),
                        shared.installer()));
    }
}
