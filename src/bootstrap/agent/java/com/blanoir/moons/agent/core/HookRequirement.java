package com.blanoir.moons.agent.core;

/** Startup hooks block readiness; other required hooks are checked when their class is observed. */
public enum HookRequirement {
    STARTUP,
    REQUIRED_WHEN_LOADED,
    OPTIONAL;

    static HookRequirement of(TargetMethod target) {
        if (!target.required()) return OPTIONAL;
        return target.id().equals("client.tick") ? STARTUP : REQUIRED_WHEN_LOADED;
    }
}
