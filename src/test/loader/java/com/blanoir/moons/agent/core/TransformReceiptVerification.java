package com.blanoir.moons.agent.core;

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
        var transformer = new MoonsTransformer(new MappingService(List.of(tick)));
        ClassLoader first = new ClassLoader() {};
        ClassLoader second = new ClassLoader() {};
        transformer.beginAttempt("one");
        require(
                transformer.transform(first, name, fixture(name)) != null,
                "Tick hook not generated");
        var receipt = transformer.receipts(first).getFirst();
        require(
                receipt.attempt().equals("one")
                        && receipt.generated()
                        && receipt.methods().getFirst().descriptor().equals("()V"),
                "Receipt lost attempt or descriptor");
        require(
                transformer.readiness(first, false).startsWith("WAITING:")
                        && transformer.readiness(second, true).startsWith("WAITING:"),
                "Unaccepted or foreign-loader hook reported ready");
        transformer.accepted(first, name, false);
        require(
                transformer.readiness(first, true).startsWith("FAILED:"),
                "JVM rejection reported ready");
        transformer.accepted(first, name, true);
        require(
                transformer.readiness(first, false).startsWith("READY:"),
                "Accepted startup hook not usable");
        transformer.deliveryFailed(first, name);
        transformer.accepted(first, name, true);
        require(
                transformer.readiness(first, true).startsWith("FAILED:")
                        && transformer.installedHooks().isEmpty(),
                "JVM accepting original bytes concealed failed native delivery");
        transformer.beginAttempt("two");
        require(
                transformer.receipts(first).isEmpty() && transformer.installedHooks().isEmpty(),
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
        var failed = new MoonsTransformer(new MappingService(List.of(invalid)));
        require(
                failed.transform(first, name, fixture(name)) == null
                        && failed.installedHooks().isEmpty(),
                "Failed writer published installed hooks");
        require(
                !failed.receipts(first).getFirst().generated()
                        && failed.readiness(first, true).startsWith("FAILED:"),
                "Failed writer reported ready");

        var late = new MoonsTransformer[1];
        var resets =
                new TargetMethod(
                        "client.tick",
                        List.of(name),
                        List.of("tick"),
                        "()V",
                        TargetMethod.HookKind.VERSION_SPECIFIC,
                        (method, id) -> {
                            late[0].beginAttempt("new");
                            return true;
                        });
        late[0] = new MoonsTransformer(new MappingService(List.of(resets)));
        late[0].transform(first, name, fixture(name));
        require(late[0].receipts(first).isEmpty(), "Old transform was credited to new attempt");
        System.out.println(
                "MOONS_TRANSFORM_RECEIPT_VERIFIED serialization loader-identity attempt-reset late-result jvm-rejection descriptors");
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
