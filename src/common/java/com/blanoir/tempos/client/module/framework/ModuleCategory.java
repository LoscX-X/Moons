package com.blanoir.tempos.client.module.framework;

import java.util.List;

/** Stable category identities in ClickGUI presentation order. */
public enum ModuleCategory {
    COMBAT("Combat"),
    PLAYER("Player"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    WORLD("World"),
    MISC("Misc"),
    NETWORK("Network"),
    EXPERIMENT("Experiment");

    private static final List<ModuleCategory> ORDERED = List.of(values());

    private final String label;

    ModuleCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<ModuleCategory> ordered() {
        return ORDERED;
    }

    @Override
    public String toString() {
        return label;
    }
}
