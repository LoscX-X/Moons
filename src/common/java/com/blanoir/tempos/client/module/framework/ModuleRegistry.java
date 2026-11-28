package com.blanoir.tempos.client.module.framework;

import static com.blanoir.tempos.client.module.framework.ModuleModel.normalizeId;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ModuleModel.Module;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns lazy catalog registration, indexed lookup, and profile-backed mutations. */
public final class ModuleRegistry {
    private static final List<Module> MODULES = new ArrayList<>();
    private static final Map<String, Module> BY_ID = new LinkedHashMap<>();
    private static final HudPolicy HUD = new HudPolicy();
    private static Runnable catalogRegistration;
    private static boolean catalogInitializing;
    private static volatile boolean catalogInitialized;
    private static List<Module> catalogSnapshot = List.of();
    private static long activationRevision;

    private ModuleRegistry() {}

    /** Installs the feature-owned catalog without forcing descriptor creation. */
    public static synchronized void installCatalog(Runnable registration) {
        Objects.requireNonNull(registration, "registration");
        if (catalogInitialized) return;
        if (catalogRegistration != null) {
            throw new IllegalStateException("Module catalog is already installed");
        }
        catalogRegistration = registration;
    }

    /** Registers descriptors on the first catalog-backed API access. */
    public static void ensureCatalog() {
        if (!catalogInitialized) initializeCatalog();
    }

    private static synchronized void initializeCatalog() {
        if (catalogInitialized) {
            return;
        }
        if (catalogInitializing) {
            throw new IllegalStateException("Recursive module catalog access");
        }
        if (catalogRegistration == null) {
            throw new IllegalStateException("Module catalog has not been installed");
        }

        catalogInitializing = true;
        try {
            catalogRegistration.run();
            catalogSnapshot = List.copyOf(MODULES);
            HUD.install(catalogSnapshot);
            catalogInitialized = true;
            catalogRegistration = null;
        } catch (RuntimeException | Error failure) {
            MODULES.clear();
            BY_ID.clear();
            throw failure;
        } finally {
            catalogInitializing = false;
        }
    }

    public static List<Module> modules() {
        ensureCatalog();
        return catalogSnapshot;
    }

    public static JsonObject snapshot() {
        return ModuleSnapshot.snapshot(modules());
    }

    public static JsonObject setEnabled(String id, boolean enabled) {
        ensureCatalog();
        Module module = BY_ID.get(normalizeId(id));
        if (module == null) {
            return error("Unknown module: " + id);
        }
        return mutate(module, () -> module.toggle().apply(Minecraft.getInstance(), enabled));
    }

    public static JsonObject setValue(String moduleId, String settingId, JsonElement value) {
        ensureCatalog();
        Module module = BY_ID.get(normalizeId(moduleId));
        if (module == null) {
            return error("Unknown module: " + moduleId);
        }
        Setting setting =
                module.settings().stream()
                        .filter(candidate -> candidate.id().equals(normalizeId(settingId)))
                        .findFirst()
                        .orElse(null);
        if (setting == null) {
            return error("Unknown setting: " + settingId);
        }
        if (!setting.isVisible() || !setting.isEnabled()) {
            return error("Setting is not available in the current mode: " + settingId);
        }
        return mutate(module, () -> setting.apply().apply(Minecraft.getInstance(), value));
    }

    /** Includes intervening on/off pairs, even when the final enabled set is unchanged. */
    public static long activationRevision() {
        return activationRevision;
    }

    private static JsonObject mutate(Module module, Runnable action) {
        boolean before = ModuleModel.safeEnabled(module.enabled());
        try {
            try (var batch = Settings.batch()) {
                ClientChat.withoutNoticesOnCurrentThread(action);
            }
            return success(module.toJson());
        } catch (RuntimeException exception) {
            return error(messageOf(exception));
        } finally {
            if (before != ModuleModel.safeEnabled(module.enabled())) activationRevision++;
        }
    }

    public static List<Module> enabledModules() {
        ensureCatalog();
        return HUD.enabledModules();
    }

    public static synchronized void add(Module module) {
        if (!catalogInitializing) {
            throw new IllegalStateException(
                    "Modules may only be added while the catalog initializes");
        }
        for (Setting setting : module.settings()) {
            Objects.requireNonNull(
                    setting.defaultValue(),
                    "Missing declared default: " + module.id() + "." + setting.id());
        }
        if (BY_ID.putIfAbsent(module.id(), module) != null) {
            throw new IllegalStateException("Duplicate module id " + module.id());
        }
        MODULES.add(module);
    }

    private static JsonObject success(JsonElement value) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("value", value);
        var saved = Settings.saveResult();
        result.addProperty("persisted", saved.saved());
        result.addProperty("appliedRevision", saved.appliedRevision());
        result.addProperty("persistedRevision", saved.persistedRevision());
        if (!saved.error().isEmpty()) result.addProperty("persistenceError", saved.error());
        return result;
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static String messageOf(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
