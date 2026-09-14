package com.blanoir.moons.ysm.internal.core.config;

/** Diagnostic policy for the local runtime. */
public final class ConfigPolicies {
    private static final Diagnostics DIAGNOSTICS = new Diagnostics();

    public static Diagnostics diagnostics() {
        return DIAGNOSTICS;
    }

    public static final class Diagnostics {
        public boolean animationDebugLog() {
            return Boolean.getBoolean("moons.ysm.debug");
        }
    }
}
