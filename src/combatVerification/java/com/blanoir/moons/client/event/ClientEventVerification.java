package com.blanoir.moons.client.event;

import com.blanoir.moons.api.ScopedResources;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Verifies lifecycle cleanup independently of a running game. */
public final class ClientEventVerification {
    private ClientEventVerification() { }

    public static void main(String[] ignoredArguments) throws Exception {
        Event<Integer> event = new Event<>();
        List<Integer> delivered = new ArrayList<>();
        try (DefaultResourceScope first = new DefaultResourceScope()) {
            ScopedResources.run(first, () -> event.register("first", delivered::add));
            try (DefaultResourceScope second = new DefaultResourceScope()) {
                ScopedResources.run(second, () -> event.register("second", EventPriority.HIGHEST,
                        value -> delivered.add(value * 10)));
                event.post(1);
                require(delivered.equals(List.of(10, 1)), "registration priority and lifetime");
            }
            event.post(2);
            require(delivered.equals(List.of(10, 1, 2)), "closing one scope preserves the other");
        }
        event.post(3);
        require(event.listenerCount() == 0 && delivered.equals(List.of(10, 1, 2)),
                "unloading removes listeners");

        try (DefaultResourceScope closed = new DefaultResourceScope()) {
            closed.close();
            try {
                ScopedResources.run(closed, () -> event.register(delivered::add));
                throw new AssertionError("closed scope accepted a listener");
            } catch (IllegalStateException expected) {
                require(event.listenerCount() == 0, "rejected listener is detached");
            }
        }

        Settings.configure(Files.createTempDirectory("moons-config-verify-"));
        ModeSetting<String> mode = new ModeSetting.Builder<String>()
                .name("verification.mode").defaultValue("default")
                .option("default", "default").option("blatant", "blatant").build();
        require(mode.tryDeserialize("BLATANT"), "case-insensitive canonical mode");
        require(!mode.tryDeserialize("balant"), "misspelled mode rejected");
        require(mode.get().equals("blatant"), "invalid input preserves current choice");
        require(Double.isInfinite(RotationUtils.angleFromView(null, Vec3.ZERO)),
                "no view produces no usable angular score");
        require(CombatInputController.consumePendingAttackHit(null) == null,
                "missing client cannot produce a pending attack hit");
        System.out.println("CLIENT_EVENTS_AND_CONFIG_VERIFIED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
