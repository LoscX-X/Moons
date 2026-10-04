package com.blanoir.moons.agent.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;

/** Exercises variant selection and duplicate prevention through the production transformer. */
final class InjectionPointVerification {
    private static final String OWNER = "verification/InjectionFixture";

    private InjectionPointVerification() {}

    static void verify() {
        TargetMethod shared = target("fixture.entry", "()V", TargetMethod.HookKind.VOID_RETURN);
        InjectionPoint point =
                new InjectionPoint(shared)
                        .withDescriptors(InjectionPoint.Environment.NEOFORGE, List.of("(I)V"))
                        .withDescriptors(InjectionPoint.Environment.FABRIC, List.of("()V"));
        if (point.variant(InjectionPoint.Environment.FORGE) != shared
                || point.variant(InjectionPoint.Environment.QUILT) != shared) {
            throw new AssertionError("Unchanged environments must reuse the shared entry");
        }
        reject(() -> point.withVariant(InjectionPoint.Environment.VANILLA, shared));
        reject(() -> point.withVariant(InjectionPoint.Environment.FABRIC, shared));
        reject(
                () ->
                        point.withVariant(
                                InjectionPoint.Environment.FORGE,
                                target("other.id", "(I)V", TargetMethod.HookKind.VOID_RETURN)));
        reject(
                () ->
                        point.withVariant(
                                InjectionPoint.Environment.FORGE,
                                target(shared.id(), "()V", TargetMethod.HookKind.VOID_HEAD)));
        reject(() -> MappingService.fromPoints(List.of(point, point)));

        // Two logical hooks intentionally share the same method; variants of one hook do not.
        MappingService mappings =
                MappingService.fromPoints(
                        List.of(
                                point,
                                new InjectionPoint(
                                        target(
                                                "fixture.second",
                                                "()V",
                                                TargetMethod.HookKind.VOID_HEAD))));
        MoonsTransformer transformer = new MoonsTransformer(mappings);
        byte[] transformed = transformer.transform(null, OWNER, fixture());
        if (transformed == null
                || !transformer.failedHooks().isEmpty()
                || transformer.installedHooks().size() != 2) {
            throw new AssertionError(
                    "Shared/variant hooks were not installed: " + transformer.failedHooks());
        }
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        for (var method : node.methods) {
            int hooks = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("com/blanoir/moons/api/bridge/AgentBridge")) hooks++;
            }
            int expected =
                    switch (method.desc) {
                        case "()V" -> 2;
                        case "(I)V" -> 1;
                        default -> 0;
                    };
            if (hooks != expected) {
                throw new AssertionError(
                        "Wrong variant/duplicate hooks in " + method.desc + ": " + hooks);
            }
        }
        if (transformer.transform(null, OWNER, transformed) != null) {
            throw new AssertionError("Retransformation duplicated variant hooks");
        }
        new ClassLoader(InjectionPointVerification.class.getClassLoader()) {
            Class<?> loadFixture() {
                return defineClass(OWNER.replace('/', '.'), transformed, 0, transformed.length);
            }
        }.loadFixture().getDeclaredMethods();
        if (!mappings.targetsForMethod(OWNER, "entry", "(J)V").isEmpty()
                || mappings.targetsClass("verification/Unrelated")) {
            throw new AssertionError("Unknown signatures/classes must remain untouched");
        }
        MoonsTransformer missing =
                new MoonsTransformer(
                        new MappingService(
                                List.of(
                                        target(
                                                "fixture.missing",
                                                "(D)V",
                                                TargetMethod.HookKind.VOID_RETURN))));
        if (missing.transform(null, OWNER, fixture()) != null
                || !missing.failedHooks().contains("fixture.missing:target-not-found")) {
            throw new AssertionError("Missing signatures must remain visible in hook diagnostics");
        }
        System.out.println(
                "MOONS_INJECTION_POINTS_VERIFIED shared=1 variants=2 duplicate-free=true");
    }

    private static TargetMethod target(String id, String descriptor, TargetMethod.HookKind hook) {
        return new TargetMethod(id, List.of(OWNER), List.of("entry"), descriptor, hook);
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        for (String descriptor : List.of("()V", "(I)V", "(J)V")) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "entry", descriptor, null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, descriptor.equals("()V") ? 1 : descriptor.equals("(J)V") ? 3 : 2);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid/ambiguous injection variant was accepted");
    }
}
