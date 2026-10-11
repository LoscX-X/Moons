package com.blanoir.moons.loader.common.asm.transform;

import com.blanoir.moons.loader.VersionMappings;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;
import java.util.jar.JarFile;

/** Reports every version boundary in one pass while retaining a failing result. */
public final class TargetInventoryVerification {
    public static void main(String[] args) throws Exception {
        var jars = new ArrayList<JarFile>();
        int missing = 0;
        try {
            for (var path : args) jars.add(new JarFile(path));
            for (var target : VersionMappings.create().allTargets()) {
                if (!target.required()) continue;
                boolean found = false;
                var candidates = new LinkedHashSet<String>();
                for (var name : target.classNames()) {
                    for (var jar : jars) {
                        var entry = jar.getJarEntry(name + ".class");
                        if (entry == null) continue;
                        var node = new ClassNode();
                        try (var input = jar.getInputStream(entry)) {
                            new ClassReader(input.readAllBytes())
                                    .accept(node, ClassReader.SKIP_CODE);
                        }
                        for (var method : node.methods) {
                            if (!target.methodNames().contains(method.name)) continue;
                            candidates.add(name + "." + method.name + method.desc);
                            if (target.descriptors().contains(method.desc)) found = true;
                        }
                    }
                }
                if (!found) {
                    missing++;
                    System.out.println(
                            target.id()
                                    + " expected="
                                    + target.classNames()
                                    + "."
                                    + target.methodNames()
                                    + target.descriptors()
                                    + " candidates="
                                    + candidates);
                }
            }
        } finally {
            for (var jar : jars) jar.close();
        }
        if (missing != 0) throw new AssertionError("Missing required targets: " + missing);
        System.out.println("MOONS_TARGET_INVENTORY_VERIFIED");
    }
}
