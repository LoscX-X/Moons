package com.blanoir.moons.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Immutable mapping table supplied by the selected version directory. */
final class MappingService {
    private final List<TargetMethod> targets;

    MappingService(List<TargetMethod> targets) {
        this.targets = List.copyOf(targets);
    }

    List<TargetMethod> targetsForClass(String internalName) {
        List<TargetMethod> matches = new ArrayList<>();
        for (TargetMethod target : targets) {
            if (target.matchesClass(internalName)) matches.add(target);
        }
        return matches;
    }

    boolean targetsClass(String internalName) {
        for (TargetMethod target : targets) {
            if (target.matchesClass(internalName)) return true;
        }
        return false;
    }

    String[] targetClassNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (TargetMethod target : targets) names.addAll(target.classNames());
        return names.toArray(String[]::new);
    }

    List<TargetMethod> allTargets() {
        return targets;
    }
}
