package com.blanoir.moons.agent.core;

import com.blanoir.moons.agent.core.hooks.HookDispatcher;
import com.blanoir.moons.api.Branding;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Retransformation-safe method-body hooks. No fields, methods or interfaces are added. */
final class MoonsTransformer {

    private final MappingService mappings;
    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> failedHooks = ConcurrentHashMap.newKeySet();

    MoonsTransformer(MappingService mappings) {
        this.mappings = mappings;
    }

    boolean targets(String className) {
        return mappings.targetsClass(className);
    }

    String[] targetClassNames() {
        return mappings.targetClassNames();
    }

    Set<String> installedHooks() {
        return Set.copyOf(installedHooks);
    }

    Set<String> failedHooks() {
        return Set.copyOf(failedHooks);
    }

    byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) {
        if (className == null || !mappings.targetsClass(className)) return null;
        List<TargetMethod> targets = mappings.targetsForClass(className);
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            reader.accept(node, 0);
            boolean changed = false;
            for (TargetMethod target : targets) {
                boolean matched = false;
                for (MethodNode method : node.methods) {
                    if (!target.matchesMethod(method.name, method.desc)) continue;
                    matched = true;
                    boolean hooked = HookDispatcher.contains(method, target);
                    if (!hooked) {
                        hooked = HookDispatcher.install(method, target);
                        changed |= hooked;
                    }
                    if (hooked) installedHooks.add(target.id());
                    else failedHooks.add(target.id() + ":load-point-not-found");
                }
                if (!matched) failedHooks.add(target.id() + ":target-not-found");
            }
            if (!changed) return null;
            boolean recomputeFrames = HookDispatcher.requiresFrames(targets);
            ClassWriter writer =
                    recomputeFrames
                            ? new LoaderAwareClassWriter(
                                    reader,
                                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                                    loader)
                            : new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failure) {
            failedHooks.add(className + ":" + failure.getClass().getSimpleName());
            System.err.println(
                    Branding.prefix()
                            + " Skipping failed transformation for "
                            + className
                            + ": "
                            + failure);
            return null;
        }
    }
}
