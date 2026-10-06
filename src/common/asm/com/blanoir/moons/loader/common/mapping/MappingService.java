package com.blanoir.moons.loader.common.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable mapping table supplied by the selected version directory. */
public final class MappingService {
    private record MethodKey(String name, String descriptor) {}

    private final List<InjectionPoint> points;
    private final List<TargetMethod> targets;
    private final Map<String, List<TargetMethod>> byClass;
    private final Map<String, Map<MethodKey, List<TargetMethod>>> byMethod;

    public MappingService(List<TargetMethod> targets) {
        this(targets.stream().map(InjectionPoint::new).toArray(InjectionPoint[]::new));
    }

    private MappingService(InjectionPoint[] points) {
        this.points = List.of(points);
        Map<String, InjectionPoint> ids = new LinkedHashMap<>();
        List<TargetMethod> all = new ArrayList<>();
        Map<String, List<TargetMethod>> classes = new LinkedHashMap<>();
        Map<String, Map<MethodKey, Map<String, TargetMethod>>> methods = new LinkedHashMap<>();
        for (InjectionPoint point : points) {
            if (ids.putIfAbsent(point.id(), point) != null) {
                throw new IllegalArgumentException("Duplicate injection point: " + point.id());
            }
            for (TargetMethod target : point.allTargets()) {
                all.add(target);
                for (String owner : target.classNames()) {
                    classes.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(target);
                    var entries = methods.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
                    for (String name : target.methodNames()) {
                        for (String descriptor : target.descriptors()) {
                            // Equivalent variants share one installation per logical hook/method.
                            entries.computeIfAbsent(
                                            new MethodKey(name, descriptor),
                                            ignored -> new LinkedHashMap<>())
                                    .putIfAbsent(target.id(), target);
                        }
                    }
                }
            }
        }
        this.targets = List.copyOf(all);
        Map<String, List<TargetMethod>> classIndex = new LinkedHashMap<>();
        classes.forEach((owner, entries) -> classIndex.put(owner, List.copyOf(entries)));
        this.byClass = java.util.Collections.unmodifiableMap(classIndex);
        Map<String, Map<MethodKey, List<TargetMethod>>> methodIndex = new LinkedHashMap<>();
        methods.forEach(
                (owner, entries) -> {
                    Map<MethodKey, List<TargetMethod>> index = new LinkedHashMap<>();
                    entries.forEach((key, hooks) -> index.put(key, List.copyOf(hooks.values())));
                    methodIndex.put(owner, Map.copyOf(index));
                });
        this.byMethod = Map.copyOf(methodIndex);
    }

    public MappingService withDescriptors(
            String id, InjectionPoint.Environment environment, List<String> descriptors) {
        List<InjectionPoint> next = new ArrayList<>(points);
        for (int index = 0; index < next.size(); index++) {
            InjectionPoint point = next.get(index);
            if (!point.id().equals(id)) continue;
            next.set(index, point.withDescriptors(environment, descriptors));
            return fromPoints(next);
        }
        throw new IllegalArgumentException("Unknown injection point: " + id);
    }

    /** Appends optional compatibility targets while retaining existing environment variants. */
    public MappingService withTargets(List<TargetMethod> targets) {
        List<InjectionPoint> next = new ArrayList<>(points);
        targets.stream().map(InjectionPoint::new).forEach(next::add);
        return fromPoints(next);
    }

    public List<InjectionPoint> injectionPoints() {
        return points;
    }

    public static MappingService fromPoints(List<InjectionPoint> points) {
        return new MappingService(points.toArray(InjectionPoint[]::new));
    }

    public List<TargetMethod> targetsForClass(String internalName) {
        return byClass.getOrDefault(internalName, List.of());
    }

    public List<TargetMethod> targetsForMethod(String owner, String name, String descriptor) {
        var methods = byMethod.get(owner);
        return methods == null
                ? List.of()
                : methods.getOrDefault(new MethodKey(name, descriptor), List.of());
    }

    public boolean targetsClass(String internalName) {
        return byClass.containsKey(internalName);
    }

    public String[] targetClassNames() {
        return byClass.keySet().toArray(String[]::new);
    }

    public List<TargetMethod> allTargets() {
        return targets;
    }
}
