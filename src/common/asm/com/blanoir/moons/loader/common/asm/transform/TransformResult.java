package com.blanoir.moons.loader.common.asm.transform;

import java.util.List;
import java.util.Set;

/** Local generation result: null bytes can mean an unchanged class, not failed generation. */
record TransformResult(
        byte[] bytes,
        List<TransformReceipt.Method> methods,
        Set<String> failures,
        boolean generated) {
    TransformResult {
        methods = List.copyOf(methods);
        failures = Set.copyOf(failures);
    }
}
