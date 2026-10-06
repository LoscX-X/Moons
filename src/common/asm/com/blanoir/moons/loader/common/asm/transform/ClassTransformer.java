package com.blanoir.moons.loader.common.asm.transform;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.loader.common.asm.hooks.HookDispatcher;
import com.blanoir.moons.loader.common.mapping.HookRequirement;
import com.blanoir.moons.loader.common.mapping.MappingService;
import com.blanoir.moons.loader.common.mapping.TargetMethod;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generates retransformation-safe method-body hooks without owning startup state. */
final class ClassTransformer {
    private final MappingService mappings;

    ClassTransformer(MappingService mappings) {
        this.mappings = mappings;
    }

    TransformResult transform(ClassLoader loader, String className, byte[] classfileBuffer) {
        if (className == null || !mappings.targetsClass(className)) return null;
        List<TargetMethod> targets = mappings.targetsForClass(className);
        var installed = new ArrayList<TransformReceipt.Method>();
        var failures = new HashSet<String>();
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            reader.accept(node, 0);
            boolean changed = false;
            Set<String> matched = new HashSet<>();
            for (MethodNode method : node.methods) {
                for (TargetMethod target :
                        mappings.targetsForMethod(className, method.name, method.desc)) {
                    matched.add(target.id());
                    boolean hooked = HookDispatcher.contains(method, target);
                    if (!hooked) {
                        hooked = HookDispatcher.install(method, target);
                        changed |= hooked;
                    }
                    if (hooked)
                        installed.add(
                                new TransformReceipt.Method(
                                        target.id(),
                                        method.name,
                                        method.desc,
                                        HookRequirement.of(target)));
                    else failures.add(target.id() + ":load-point-not-found");
                }
            }
            for (TargetMethod target : targets) {
                if (!matched.contains(target.id())) failures.add(target.id() + ":target-not-found");
            }
            if (!changed) {
                return new TransformResult(null, installed, failures, true);
            }
            boolean recomputeFrames = HookDispatcher.requiresFrames(targets);
            ClassWriter writer =
                    recomputeFrames
                            ? new LoaderAwareClassWriter(
                                    reader,
                                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                                    loader)
                            : new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] output = writer.toByteArray();
            return new TransformResult(output, installed, failures, true);
        } catch (Throwable failure) {
            failures.add(className + ":" + failure.getClass().getSimpleName());
            System.err.println(
                    Branding.prefix()
                            + " Skipping failed transformation for "
                            + className
                            + ": "
                            + failure);
            return new TransformResult(null, installed, failures, false);
        }
    }
}
