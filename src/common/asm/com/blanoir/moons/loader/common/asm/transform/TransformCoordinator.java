package com.blanoir.moons.loader.common.asm.transform;

import com.blanoir.moons.loader.common.mapping.MappingService;
import com.blanoir.moons.loader.common.mapping.TargetMethod;

/** Coordinates local generation with attempt-scoped receipts and startup policy. */
public final class TransformCoordinator {
    private final MappingService mappings;
    private final ClassTransformer transformer;
    private final TransformLedger ledger = new TransformLedger();
    private final HookReadiness readiness;

    public TransformCoordinator(MappingService mappings) {
        this.mappings = mappings;
        transformer = new ClassTransformer(mappings);
        readiness = new HookReadiness(mappings);
    }

    TransformLedger ledger() {
        return ledger;
    }

    public void beginAttempt(String attempt) {
        ledger.beginAttempt(attempt);
    }

    public void accepted(ClassLoader loader, String className, boolean success) {
        ledger.accepted(loader, className, success);
    }

    public void deliveryFailed(ClassLoader loader, String className) {
        ledger.deliveryFailed(loader, className);
    }

    public String readiness(ClassLoader loader, boolean firstTick) {
        synchronized (ledger) {
            return readiness.evaluate(ledger.receipts(loader), firstTick);
        }
    }

    public java.util.Set<String> installedHooks() {
        return ledger.installedHooks();
    }

    public java.util.Set<String> failedHooks() {
        return ledger.failedHooks();
    }

    public boolean targets(String className) {
        return mappings.targetsClass(className);
    }

    public boolean requiredClass(String className) {
        return mappings.targetsForClass(className).stream().anyMatch(TargetMethod::required);
    }

    public String[] targetClassNames() {
        return mappings.targetClassNames();
    }

    public byte[] transform(ClassLoader loader, String className, byte[] bytes) {
        var attempt = ledger.currentAttempt();
        var result = transformer.transform(loader, className, bytes);
        if (result == null) return null;
        ledger.record(
                attempt,
                loader,
                className,
                result.methods(),
                result.failures(),
                result.generated());
        return result.bytes();
    }
}
