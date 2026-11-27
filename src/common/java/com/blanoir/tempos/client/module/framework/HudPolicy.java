package com.blanoir.tempos.client.module.framework;

import static com.blanoir.tempos.client.module.framework.ModuleModel.safeEnabled;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ModuleModel.Module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Maintains the sorted candidate list and reuses unchanged HUD snapshots. */
final class HudPolicy {
    private static final Set<String> HUD_HIDDEN_MODULES =
            Set.of("clickgui", "hud", "xraytargets", "chatprefix", "movefix");
    private List<HudCandidate> hudCandidates = List.of();
    private volatile List<Module> enabledSnapshot = List.of();

    private record HudCandidate(Module module, String hiddenKey) {}

    void install(List<Module> modules) {
        hudCandidates =
                modules.stream()
                        .filter(module -> !HUD_HIDDEN_MODULES.contains(module.id()))
                        .sorted(
                                Comparator.comparingInt((Module module) -> module.name().length())
                                        .reversed())
                        .map(module -> new HudCandidate(module, "module." + module.id() + ".hide"))
                        .toList();
    }

    List<Module> enabledModules() {
        List<Module> previous = enabledSnapshot;
        List<Module> changed = null;
        int count = 0;
        // Keep enabled/visibility reads live, but allocate only when membership changes.
        // Names and catalog order are immutable, so they need sorting only once.
        for (int index = 0; index < hudCandidates.size(); index++) {
            HudCandidate candidate = hudCandidates.get(index);
            Module module = candidate.module();
            if (!safeEnabled(module.enabled()) || Settings.getBoolean(candidate.hiddenKey(), false))
                continue;
            if (changed == null && (count >= previous.size() || previous.get(count) != module)) {
                changed = new ArrayList<>(previous.size() + 1);
                changed.addAll(previous.subList(0, count));
            }
            if (changed != null) changed.add(module);
            count++;
        }
        if (changed != null) {
            List<Module> next = List.copyOf(changed);
            enabledSnapshot = next;
            return next;
        }
        if (count != previous.size()) {
            List<Module> next = List.copyOf(previous.subList(0, count));
            enabledSnapshot = next;
            return next;
        }
        return previous;
    }

    static boolean hudVisible(Module module) {
        return !HUD_HIDDEN_MODULES.contains(module.id())
                && !Settings.getBoolean("module." + module.id() + ".hide", false);
    }
}
