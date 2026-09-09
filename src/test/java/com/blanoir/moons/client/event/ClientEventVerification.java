package com.blanoir.moons.client.event;

import com.blanoir.moons.api.ScopedResources;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.combat.Velocity;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Verifies lifecycle cleanup independently of a running game. */
public final class ClientEventVerification {
    private ClientEventVerification() {}

    public static void main(String[] ignoredArguments) throws Exception {
        Event<Integer> event = new Event<>();
        List<Integer> delivered = new ArrayList<>();
        try (DefaultResourceScope first = new DefaultResourceScope()) {
            ScopedResources.run(first, () -> event.register("first", delivered::add));
            try (DefaultResourceScope second = new DefaultResourceScope()) {
                ScopedResources.run(
                        second,
                        () ->
                                event.register(
                                        "second",
                                        EventPriority.HIGHEST,
                                        value -> delivered.add(value * 10)));
                event.post(1);
                require(delivered.equals(List.of(10, 1)), "registration priority and lifetime");
            }
            event.post(2);
            require(delivered.equals(List.of(10, 1, 2)), "closing one scope preserves the other");
        }
        event.post(3);
        require(
                event.listenerCount() == 0 && delivered.equals(List.of(10, 1, 2)),
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

        Path configDirectory = Files.createTempDirectory("moons-config-verify-");
        Settings.configure(configDirectory);
        ModeSetting<String> mode =
                new ModeSetting.Builder<String>()
                        .name("verification.mode")
                        .defaultValue("default")
                        .option("default", "default")
                        .option("blatant", "blatant")
                        .build();
        require(mode.tryDeserialize("BLATANT"), "case-insensitive canonical mode");
        require(!mode.tryDeserialize("balant"), "misspelled mode rejected");
        require(mode.get().equals("blatant"), "invalid input preserves current choice");
        mode.set("default");
        require(mode.serialized().equals("default"), "typed mutation updates cached mode");
        mode.deserialize("BLATANT");
        require(mode.get().equals("blatant"), "deserialization updates cached mode");
        Settings.setString("velocity.mode", "vanilla");
        Velocity.init();
        require(
                Velocity.modeOptions().equals(List.of("normal", "jumpreset")),
                "Velocity exposes only its two retained modes");
        require(
                Velocity.normalMode() && Settings.getString("velocity.mode", "").equals("normal"),
                "old Vanilla configurations keep ordinary knockback scaling");
        Velocity.setMode(null, "grim2371");
        require(Velocity.jumpResetMode(), "removed modes fall back to JumpReset");
        Velocity.setMode(null, "normal");
        require(Velocity.normalMode(), "Normal remains selectable");
        require(
                Double.isInfinite(RotationUtils.angleFromView(null, Vec3.ZERO)),
                "no view produces no usable angular score");
        require(
                CombatInputController.consumePendingAttackHit(null) == null,
                "missing client cannot produce a pending attack hit");
        verifyCatalogSnapshots();
        verifyUnchangedSettings(configDirectory);
        System.out.println("CLIENT_EVENTS_AND_CONFIG_VERIFIED");
    }

    private static void verifyCatalogSnapshots() {
        AtomicBoolean shortEnabled = new AtomicBoolean(true);
        AtomicBoolean longEnabled = new AtomicBoolean(true);
        ModuleRegistry.Module shortModule =
                new ModuleRegistry.Module(
                        "perf_short",
                        "Tiny",
                        "misc",
                        shortEnabled::get,
                        (client, enabled) -> 1,
                        () -> "",
                        List.of());
        ModuleRegistry.Module longModule =
                new ModuleRegistry.Module(
                        "perf_long",
                        "Longer module",
                        "misc",
                        longEnabled::get,
                        (client, enabled) -> 1,
                        () -> "",
                        List.of());
        ModuleRegistry.installCatalog(
                () -> {
                    ModuleRegistry.add(shortModule);
                    ModuleRegistry.add(longModule);
                });
        List<ModuleRegistry.Module> both = ModuleRegistry.enabledModules();
        require(both.equals(List.of(longModule, shortModule)), "HUD keeps length order");
        require(ModuleRegistry.enabledModules() == both, "unchanged membership reuses snapshot");
        longEnabled.set(false);
        require(
                ModuleRegistry.enabledModules().equals(List.of(shortModule)),
                "live disable is immediate");
        require(both.size() == 2, "old snapshots remain immutable after a toggle");
        longEnabled.set(true);
        Settings.setBoolean("module.perf_short.hide", true);
        require(
                ModuleRegistry.enabledModules().equals(List.of(longModule)),
                "hide changes are immediate");
        Settings.setBoolean("module.perf_short.hide", false);
        require(ModuleRegistry.enabledModules().equals(both), "restored entries keep ordering");
        shortEnabled.set(false);
        longEnabled.set(false);
        require(ModuleRegistry.enabledModules().isEmpty(), "all-disabled snapshot is empty");
    }

    private static void verifyUnchangedSettings(Path configDirectory) throws Exception {
        Settings.setString("verification.unchanged", "value");
        Path file = configDirectory.resolve("moons.properties");
        FileTime sentinel = FileTime.fromMillis(1_000L);
        Files.setLastModifiedTime(file, sentinel);
        Settings.setString("verification.unchanged", "value");
        require(
                Files.getLastModifiedTime(file).equals(sentinel),
                "same setting value avoids disk rewrite");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
