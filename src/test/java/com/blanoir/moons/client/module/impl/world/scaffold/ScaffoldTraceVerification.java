package com.blanoir.moons.client.module.impl.world.scaffold;

import java.util.List;

public final class ScaffoldTraceVerification {
    public static void main(String[] args) {
        ScaffoldTraceBuffer buffer = new ScaffoldTraceBuffer(3);
        buffer.add("ATTEMPT");
        buffer.add("USE_ON seq=5");
        buffer.add("MOVE");
        List<String> firstAlert = buffer.snapshot();
        buffer.add("RECEIVED_ALERT post-flying");
        require(
                buffer.snapshot()
                        .equals(List.of("USE_ON seq=5", "MOVE", "RECEIVED_ALERT post-flying")),
                "overflow evicts oldest while preserving packet order");
        require(buffer.discarded() == 1, "export explicitly reports discarded history");
        buffer.add("RECEIVED_ALERT pre-flying");
        require(
                firstAlert.equals(List.of("ATTEMPT", "USE_ON seq=5", "MOVE")),
                "later flags cannot overwrite an in-flight first-alert snapshot");
        buffer.clear();
        buffer.add("NEW_CONNECTION");
        require(
                buffer.discarded() == 0 && buffer.snapshot().equals(List.of("NEW_CONNECTION")),
                "new sessions do not inherit old connection history");
        require(firstAlert.size() == 3, "disconnect cannot erase a pending export");
        try {
            firstAlert.add("mutation");
            throw new AssertionError("snapshot must be immutable for async writing");
        } catch (UnsupportedOperationException expected) {
        }
        for (int i = 0; i < 10000; i++) buffer.add("packet=" + i);
        require(
                buffer.snapshot().size() == 3 && buffer.snapshot().getFirst().equals("packet=9997"),
                "long sessions have bounded memory");
        System.out.println("ScaffoldTraceVerification passed (7 checks)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
