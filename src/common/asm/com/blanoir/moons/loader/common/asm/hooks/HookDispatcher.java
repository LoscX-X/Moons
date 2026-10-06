package com.blanoir.moons.loader.common.asm.hooks;

import com.blanoir.moons.loader.common.mapping.TargetMethod;

import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Delegates hook operations to the selected definition. */
public final class HookDispatcher {
    private HookDispatcher() {}

    public static boolean install(MethodNode method, TargetMethod target) {
        return HookStrategy.forTarget(target).installer().test(method, target);
    }

    public static boolean contains(MethodNode method, TargetMethod target) {
        return HookStrategy.forTarget(target).detector().test(method, target);
    }

    public static boolean requiresFrames(List<TargetMethod> targets) {
        return targets.stream().anyMatch(target -> HookStrategy.forTarget(target).requiresFrames());
    }
}
