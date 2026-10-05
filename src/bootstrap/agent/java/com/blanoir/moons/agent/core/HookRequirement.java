package com.blanoir.moons.agent.core;

/** Startup matching blocks readiness; other hook misses remain diagnostics as in legacy loaders. */
public enum HookRequirement {
    STARTUP,
    REQUIRED_WHEN_LOADED,
    OPTIONAL;

    static HookRequirement of(TargetMethod target) {
        if (!target.required()) return OPTIONAL;
        return target.id().equals("client.tick") ? STARTUP : REQUIRED_WHEN_LOADED;
    }
}
