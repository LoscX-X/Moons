package com.blanoir.moons.loader.common.asm.transform;

import com.blanoir.moons.loader.common.mapping.HookRequirement;
import com.blanoir.moons.loader.common.mapping.MappingService;
import com.blanoir.moons.loader.common.mapping.TargetMethod;

import java.util.List;

/** Startup policy over a receipt snapshot; does not mutate transformation state. */
final class HookReadiness {
    private final MappingService mappings;

    HookReadiness(MappingService mappings) {
        this.mappings = mappings;
    }

    String evaluate(List<TransformReceipt> receipts, boolean firstTick) {
        boolean tick = false;
        var unavailable = new java.util.TreeSet<String>();
        for (var receipt : receipts) {
            if (mappings.targetsForClass(receipt.className()).stream()
                    .noneMatch(TargetMethod::required)) continue;
            if (!receipt.generated()
                    || receipt.acceptance() == TransformReceipt.Acceptance.REJECTED)
                return "FAILED:transform:" + receipt.className();
            for (String failure : receipt.failures()) {
                if (failure.startsWith("optional.")) continue;
                if (runtimeHookMiss(receipt.className(), failure)) unavailable.add(failure);
                else return "FAILED:hook:" + failure;
            }
            boolean startup =
                    receipt.methods().stream()
                            .anyMatch(method -> method.requirement() == HookRequirement.STARTUP);
            if (receipt.acceptance() == TransformReceipt.Acceptance.UNKNOWN
                    && !(startup && firstTick))
                return "WAITING:jvm-acceptance:" + receipt.className();
            if (receipt.methods().stream()
                    .anyMatch(method -> method.requirement() == HookRequirement.STARTUP)) {
                // An executed first tick proves JVM definition of a naturally loaded tick class.
                tick |= receipt.acceptance() == TransformReceipt.Acceptance.ACCEPTED || firstTick;
            }
        }
        if (!tick) return "WAITING:client.tick";
        return "READY:startup-hooks"
                + (unavailable.isEmpty()
                        ? ""
                        : ";unavailable-hooks=" + String.join(",", unavailable));
    }

    /** Preserve legacy matching tolerance without concealing transformation or delivery errors. */
    private boolean runtimeHookMiss(String className, String failure) {
        return mappings.targetsForClass(className).stream()
                .anyMatch(
                        target ->
                                HookRequirement.of(target) != HookRequirement.STARTUP
                                        && (failure.equals(target.id() + ":load-point-not-found")
                                                || failure.equals(
                                                        target.id() + ":target-not-found")));
    }
}
