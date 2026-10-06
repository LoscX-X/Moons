package com.blanoir.moons.loader.common.asm.transform;

import com.blanoir.moons.loader.common.mapping.MappingService;
import com.blanoir.moons.loader.common.mapping.TargetMethod;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;

/** Failed serialization, loader identity, attempt reset and JVM rejection cannot report READY. */
final class TransformReceiptVerification {
    static void verify() {
        String name = "fixture/Receipt";
        var tick =
                new TargetMethod(
                        "client.tick",
                        List.of(name),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.VOID_HEAD);
        var transformer = new TransformCoordinator(new MappingService(List.of(tick)));
        ClassLoader first = new ClassLoader() {};
        ClassLoader second = new ClassLoader() {};
        transformer.ledger().beginAttempt("one");
        require(
                transformer.transform(first, name, fixture(name)) != null,
                "Tick hook not generated");
        var receipt = transformer.ledger().receipts(first).getFirst();
        require(
                receipt.attempt().equals("one")
                        && receipt.generated()
                        && receipt.methods().getFirst().descriptor().equals("()V"),
                "Receipt lost attempt or descriptor");
        require(
                transformer.readiness(first, false).startsWith("WAITING:")
                        && transformer.readiness(second, true).startsWith("WAITING:"),
                "Unaccepted or foreign-loader hook reported ready");
        transformer.ledger().accepted(first, name, false);
        require(
                transformer.readiness(first, true).startsWith("FAILED:"),
                "JVM rejection reported ready");
        transformer.ledger().accepted(first, name, true);
        require(
                transformer.readiness(first, false).startsWith("READY:"),
                "Accepted startup hook not usable");
        transformer.ledger().deliveryFailed(first, name);
        transformer.ledger().accepted(first, name, true);
        require(
                transformer.readiness(first, true).startsWith("FAILED:")
                        && transformer.ledger().installedHooks().isEmpty(),
                "JVM accepting original bytes concealed failed native delivery");
        transformer.ledger().beginAttempt("two");
        require(
                transformer.ledger().receipts(first).isEmpty()
                        && transformer.ledger().installedHooks().isEmpty(),
                "New attempt retained old receipts");

        var invalid =
                new TargetMethod(
                        "client.tick",
                        List.of(name),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.VERSION_SPECIFIC,
                        (method, id) -> {
                            method.name = null;
                            return true;
                        });
        var failed = new TransformCoordinator(new MappingService(List.of(invalid)));
        require(
                failed.transform(first, name, fixture(name)) == null
                        && failed.ledger().installedHooks().isEmpty(),
                "Failed writer published installed hooks");
        require(
                !failed.ledger().receipts(first).getFirst().generated()
                        && failed.readiness(first, true).startsWith("FAILED:"),
                "Failed writer reported ready");

        var late = new TransformCoordinator[1];
        var resets =
                new TargetMethod(
                        "client.tick",
                        List.of(name),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.VERSION_SPECIFIC,
                        (method, id) -> {
                            late[0].ledger().beginAttempt("new");
                            return true;
                        });
        late[0] = new TransformCoordinator(new MappingService(List.of(resets)));
        late[0].transform(first, name, fixture(name));
        require(
                late[0].ledger().receipts(first).isEmpty(),
                "Old transform was credited to new attempt");
        verifyLegacyMatching(first);
        System.out.println(
                "MOONS_TRANSFORM_RECEIPT_VERIFIED serialization loader-identity attempt-reset late-result jvm-rejection descriptors legacy-matching");
    }

    private static void verifyLegacyMatching(ClassLoader loader) {
        String tickClass = "fixture/Startup";
        String renderClass = "fixture/Renderer";
        var tick =
                new TargetMethod(
                        "client.tick",
                        List.of(tickClass),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.VOID_HEAD);
        var world =
                new TargetMethod(
                        "client.world-render",
                        List.of(renderClass),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.WORLD_RENDER);
        var hud =
                new TargetMethod(
                        "client.hud",
                        List.of(tickClass),
                        List.of("missingHud"),
                        "()V",
                        TargetMethod.HookKind.HUD);
        var transformer = new TransformCoordinator(new MappingService(List.of(tick, world, hud)));
        transformer.transform(loader, tickClass, fixture(tickClass));
        transformer.ledger().accepted(loader, tickClass, true);
        require(
                transformer.transform(loader, renderClass, fixture(renderClass)) == null,
                "Missing render anchors unexpectedly generated a hook");
        transformer.ledger().accepted(loader, renderClass, true);
        String status = transformer.readiness(loader, true);
        require(
                status.startsWith("READY:")
                        && status.contains("client.world-render:load-point-not-found")
                        && status.contains("client.hud:target-not-found"),
                "Legacy hook misses blocked startup or lost diagnostics: " + status);
        require(
                transformer.ledger().installedHooks().equals(java.util.Set.of("client.tick"))
                        && transformer
                                .ledger()
                                .failedHooks()
                                .contains("client.world-render:load-point-not-found"),
                "Unavailable hook was reported installed or its failure was hidden");
        transformer.ledger().accepted(loader, renderClass, false);
        require(
                transformer.readiness(loader, true).startsWith("FAILED:transform:"),
                "Runtime hook tolerance concealed JVM rejection");
        transformer.ledger().accepted(loader, renderClass, true);
        transformer.ledger().deliveryFailed(loader, renderClass);
        require(
                transformer.readiness(loader, true).startsWith("FAILED:transform:"),
                "Runtime hook tolerance concealed failed native delivery");

        // Use an unmatched startup signature, even when the runtime reports a first tick.
        var unmatchedTick =
                new TargetMethod(
                        "client.tick",
                        List.of(tickClass),
                        List.of("missingTick"),
                        "()V",
                        TargetMethod.HookKind.VOID_HEAD);
        var missingTick = new TransformCoordinator(new MappingService(List.of(unmatchedTick)));
        missingTick.transform(loader, tickClass, fixture(tickClass));
        missingTick.ledger().accepted(loader, tickClass, true);
        require(
                missingTick
                        .readiness(loader, true)
                        .equals("FAILED:hook:client.tick:target-not-found"),
                "Missing startup hook was tolerated");
        var noTickAnchor =
                new TargetMethod(
                        "client.tick",
                        List.of(tickClass),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.WORLD_RENDER);
        missingTick = new TransformCoordinator(new MappingService(List.of(noTickAnchor)));
        missingTick.transform(loader, tickClass, fixture(tickClass));
        missingTick.ledger().accepted(loader, tickClass, true);
        require(
                missingTick
                        .readiness(loader, true)
                        .equals("FAILED:hook:client.tick:load-point-not-found"),
                "Missing startup anchor was tolerated");
    }

    private static byte[] fixture(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
