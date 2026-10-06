package com.blanoir.moons.client;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.catalog.ModuleCatalog;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.google.gson.JsonParser;

import net.minecraft.init.Bootstrap;

import java.nio.file.*;
import java.util.*;

/** Instantiates the real catalog and exercises retained config descriptors without enabling modules. */
public final class LegacyCatalogVerification {
    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Isolated configuration directory required");
        Settings.configure(Path.of(args[0]));
        Bootstrap.register();
        ModuleRegistry.installCatalog(ModuleCatalog::register);
        List<ModuleRegistry.Module> modules = ModuleRegistry.modules();
        if (modules.size() != 64)
            throw new AssertionError("Expected 64 applicable modules, got " + modules.size());
        Set<String> ids = new TreeSet<>();
        int settings = 0;
        for (var module : modules) {
            if (!ids.add(module.id())) throw new AssertionError("Duplicate module " + module.id());
            Set<String> fields = new HashSet<>();
            for (var setting : module.settings()) {
                if (!fields.add(setting.id()))
                    throw new AssertionError(
                            "Duplicate setting " + module.id() + "." + setting.id());
                if (setting.defaultValue() == null || setting.value().get() == null)
                    throw new AssertionError(
                            "Missing setting value " + module.id() + "." + setting.id());
                setting.isVisible();
                setting.isEnabled();
                settings++;
            }
        }
        for (String absent : List.of("automace", "autospear", "autototem"))
            if (ids.contains(absent))
                throw new AssertionError("Unavailable item module still registered: " + absent);
        for (String retained :
                List.of(
                        "silentaura",
                        "aimassist",
                        "autoblock",
                        "autoclicker",
                        "hitselect",
                        "misplace",
                        "reach",
                        "triggerbot",
                        "critical",
                        "sprintreset",
                        "jumpreset",
                        "keepsprint",
                        "movefix",
                        "sprint",
                        "nojumpdelay"))
            if (!ids.contains(retained))
                throw new AssertionError("Applicable module missing: " + retained);
        var aura =
                modules.stream()
                        .filter(module -> module.id().equals("silentaura"))
                        .findFirst()
                        .orElseThrow();
        if (SilentAuraConfig.settings().length != 47 || aura.settings().size() != 48)
            throw new AssertionError(
                    "SilentAura must keep 47 Legacy settings plus the shared HUD hide setting");
        for (var setting : aura.settings())
            if (setting.id().equals("mode")
                    || setting.id().contains("cooldown")
                    || setting.id().contains("charge"))
                throw new AssertionError("Modern combat setting survived: " + setting.id());
        for (String mode : List.of("lock", "balance", "full_lock")) {
            if (!SilentAuraConfig.aimMode(mode))
                throw new AssertionError("Original aim mode missing: " + mode);
            for (var setting : aura.settings()) {
                if (setting.id().equals("smooth")
                        && setting.isVisible() == mode.equals("full_lock"))
                    throw new AssertionError("Smoothing visibility changed");
                if (setting.id().equals("learned_assist") && !setting.isVisible())
                    throw new AssertionError("Learned assist missing");
            }
        }
        SilentAuraConfig.aimMode("balance");
        SilentAuraConfig.cps(8, 12);
        Settings.save();
        var stored =
                JsonParser.parseString(Files.readString(Settings.file()))
                        .getAsJsonObject()
                        .getAsJsonObject("values");
        if (!Settings.saveResult().saved()
                || !"balance".equals(stored.get("silentaura.aimMode").getAsString()))
            throw new AssertionError("Aim mode persistence failed");
        System.out.println(
                "MINECRAFT189_CATALOG_VERIFIED modules="
                        + modules.size()
                        + " descriptors="
                        + settings
                        + " silentaura=47 legacy-settings aim-modes=3 config=persisted ids="
                        + ids);
    }
}
