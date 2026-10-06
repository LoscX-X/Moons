package com.blanoir.moons.client.input;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Releases only activations acquired by a held input; existing activations stay owned elsewhere. */
public final class HeldBindingState<K> {
    private final Map<K, Set<String>> held = new LinkedHashMap<>();
    private final Predicate<String> enabled;
    private final BiConsumer<String, Boolean> setEnabled;

    public HeldBindingState(Predicate<String> enabled, BiConsumer<String, Boolean> setEnabled) {
        this.enabled = enabled;
        this.setEnabled = setEnabled;
    }

    public void press(K key, List<String> targets) {
        for (String target : targets) {
            if (owned(target)) {
                held.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(target);
            } else if (!enabled.test(target)) {
                // Claim before calling feature code, which may synchronously reset input.
                held.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(target);
                setEnabled.accept(target, true);
            }
        }
    }

    public void release(K key) {
        Set<String> targets = held.remove(key);
        if (targets == null) return;
        for (String target : targets) {
            if (!owned(target)) setEnabled.accept(target, false);
        }
    }

    public void releaseTarget(String target) {
        boolean removed = false;
        for (Set<String> targets : held.values()) removed |= targets.remove(target);
        held.values().removeIf(Set::isEmpty);
        if (removed) setEnabled.accept(target, false);
    }

    public void retain(BiPredicate<K, String> keep) {
        Set<String> removed = new LinkedHashSet<>();
        for (var entry : held.entrySet()) {
            entry.getValue()
                    .removeIf(
                            target -> {
                                if (keep.test(entry.getKey(), target)) return false;
                                removed.add(target);
                                return true;
                            });
        }
        held.values().removeIf(Set::isEmpty);
        for (String target : removed) {
            if (!owned(target)) setEnabled.accept(target, false);
        }
    }

    public void reset() {
        Set<String> targets = new LinkedHashSet<>();
        held.values().forEach(targets::addAll);
        held.clear();
        targets.forEach(target -> setEnabled.accept(target, false));
    }

    private boolean owned(String target) {
        return held.values().stream().anyMatch(targets -> targets.contains(target));
    }
}
