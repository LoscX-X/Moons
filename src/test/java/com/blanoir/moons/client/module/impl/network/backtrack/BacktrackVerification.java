package com.blanoir.moons.client.module.impl.network.backtrack;

/** Regressions for artificial burst pacing and old opaque ESP configurations. */
public final class BacktrackVerification {
    public static void main(String[] args) throws Exception {
        verifyLoginPassesBeforePlayerExists();
        long previous = 0;
        for (int i = 0; i < 256; i++) {
            previous = BacktrackTiming.deadline(1000, 100, previous);
            require(previous == 1100, "A burst must not acquire one extra millisecond per packet");
            require(!BacktrackTiming.due(1099, previous), "No early replay");
            require(BacktrackTiming.due(1100, previous), "The whole burst expires at its deadline");
        }
        long first = BacktrackTiming.deadline(1000, 150, 0);
        long second = BacktrackTiming.deadline(1020, 100, first);
        long third = BacktrackTiming.deadline(1030, 100, second);
        require(first == second && second == third, "A shorter delay cannot reorder movement");
        require(
                BacktrackTiming.deadline(1200, 100, third) == 1300,
                "An expired burst must not add latency to new movement");
        require(
                BacktrackVisual.fill(0xffffffff) == 0x14ffffff,
                "A saved opaque color must not obscure the enemy");
        require(
                BacktrackVisual.outline(0xff56cfe1) == 0xbe56cfe1,
                "Outline retains its hue with bounded opacity");
        require(
                BacktrackVisual.fill(0x0056cfe1) == 0x0056cfe1,
                "An explicitly disabled fill remains transparent");
        System.out.println("BACKTRACK_VERIFIED");
    }

    private static void verifyLoginPassesBeforePlayerExists() throws Exception {
        var enabledField =
                com.blanoir.moons.client.module.impl.network.Backtrack.class.getDeclaredField(
                        "ENABLED");
        enabledField.setAccessible(true);
        var enabled =
                (com.blanoir.moons.client.config.settings.BooleanSetting) enabledField.get(null);
        boolean previous = enabled.get();
        var listener =
                (net.minecraft.network.protocol.game.ClientGamePacketListener)
                        java.lang.reflect.Proxy.newProxyInstance(
                                BacktrackVerification.class.getClassLoader(),
                                new Class<?>[] {
                                    net.minecraft.network.protocol.game.ClientGamePacketListener
                                            .class
                                },
                                (proxy, method, arguments) -> {
                                    throw new AssertionError(
                                            "Login listener must remain untouched");
                                });
        // 26.2 adds a login flag. Build the version's record without running a
        // world bootstrap; payload fields are never inspected by this policy.
        var components =
                net.minecraft.network.protocol.game.ClientboundLoginPacket.class
                        .getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] =
                    types[i] == boolean.class
                            ? Boolean.FALSE
                            : types[i] == int.class
                                    ? Integer.valueOf(0)
                                    : types[i] == java.util.Set.class ? java.util.Set.of() : null;
        }
        var login =
                net.minecraft.network.protocol.game.ClientboundLoginPacket.class
                        .getDeclaredConstructor(types)
                        .newInstance(values);
        try {
            enabled.set(true);
            // No Minecraft/player instance exists in this test, just as no LocalPlayer
            // exists before the login packet initializes the world during connection.
            require(
                    !com.blanoir.moons.client.module.impl.network.Backtrack.handleIncomingPacket(
                            login, listener),
                    "Login must reach vanilla without accessing a player or wrapping it for replay");
        } finally {
            enabled.set(previous);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
