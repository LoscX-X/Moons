package com.blanoir.tempos.client.module.impl.misc;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.input.BindActivationMode;
import com.blanoir.tempos.client.input.InputKeys;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleKeybinds;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;
import com.google.gson.JsonPrimitive;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the real registry toggle route and Panic's saved set, without a game. */
public final class PanicVerification {
    private static final AtomicBoolean first = new AtomicBoolean(),
            second = new AtomicBoolean(),
            third = new AtomicBoolean();

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path fixtureHome = Path.of(System.getProperty("tempos.home"));
        java.nio.file.Files.createDirectories(fixtureHome);
        Settings.configure(java.nio.file.Files.createTempDirectory(fixtureHome, "config-"));
        ModuleRegistry.installCatalog(
                () -> {
                    add("first", first);
                    add("second", second);
                    add("third", third);
                    ModuleRegistry.add(Panic.INSTANCE.toDescriptor());
                });
        toggle("first", true);
        toggle("second", true);
        toggle("panic", true);
        require(
                !first.get() && !second.get() && !third.get() && !Panic.INSTANCE.isEnabled(),
                "Panic failed to disable all");
        toggle("panic", true);
        require(!first.get() && !second.get(), "Restored with Re-enable off");
        require(
                ModuleRegistry.setValue("panic", "re_enable", new JsonPrimitive(true))
                        .get("ok")
                        .getAsBoolean(),
                "Re-enable control");
        toggle("first", true);
        toggle("second", true);
        toggle("panic", true);
        require(!first.get() && !second.get(), "Armed Panic did not disable");
        toggle("panic", true);
        require(
                first.get() && second.get() && !third.get(),
                "Did not restore exactly the previous enabled set");
        toggle("panic", true);
        toggle("third", true);
        toggle("third", false);
        toggle("panic", true);
        require(
                !first.get() && !second.get() && !third.get(),
                "Manual on/off pair retained stale restore set");
        toggle("first", true);
        toggle("panic", true);
        toggle("panic", false);
        toggle("panic", true);
        require(!first.get(), "Profile disable retained stale restore set");
        toggle("second", true);
        toggle("panic", true);
        ModuleRegistry.setValue("panic", "re_enable", new JsonPrimitive(false));
        toggle("panic", true);
        require(!second.get(), "Disabling Re-enable retained its saved set");
        var key = InputKeys.parse("key.keyboard.p");
        Settings.setString("keybind.panic", "key.keyboard.p");
        Settings.setString("keybind.third", "key.keyboard.p");
        ModuleKeybinds.setActivationMode("third", BindActivationMode.HOLD);
        toggle("first", true);
        toggle("second", true);
        ModuleKeybinds.dispatchPress(key, true);
        require(
                !first.get() && !second.get() && !third.get(),
                "A shared held binding re-enabled a module after Panic");
        ModuleKeybinds.dispatchRelease(key);
        System.out.println(
                "TEMPOS_PANIC_VERIFIED one-shot disable-all optional-restore exact-set manual-toggle-invalidation profile-reset option-reset batched-persistence");
    }

    private static void add(String id, AtomicBoolean enabled) {
        ModuleRegistry.add(
                ModuleModel.module(
                        id,
                        id,
                        ModuleCategory.MISC,
                        enabled::get,
                        (client, value) -> {
                            enabled.set(value);
                            return 1;
                        },
                        () -> ""));
    }

    private static void toggle(String id, boolean enabled) {
        require(
                ModuleRegistry.setEnabled(id, enabled).get("ok").getAsBoolean(),
                "Registry rejected " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
