package com.blanoir.tempos.client.module.framework;

import static com.blanoir.tempos.client.module.framework.ModuleModel.safeEnabled;

import com.blanoir.tempos.client.module.framework.ModuleModel.Module;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** Serializes the catalog and its live setting values. */
final class ModuleSnapshot {
    private ModuleSnapshot() {}

    static JsonObject snapshot(List<Module> catalog) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonArray categories = new JsonArray();
        for (ModuleCategory category : ModuleCategory.ordered()) {
            categories.add(category.label());
        }
        root.add("categories", categories);
        JsonArray modules = new JsonArray();
        catalog.forEach(module -> modules.add(module.toJson()));
        root.add("modules", modules);
        return root;
    }

    static JsonObject module(Module module) {
        JsonObject json = new JsonObject();
        json.addProperty("id", module.id());
        json.addProperty("name", module.name());
        json.addProperty("description", module.description());
        json.addProperty("category", module.category().label());
        json.addProperty("enabled", safeEnabled(module.enabled()));
        json.addProperty("hudVisible", HudPolicy.hudVisible(module));
        String currentTag = module.tag().get();
        json.addProperty("tag", currentTag == null ? "" : currentTag);
        JsonArray values = new JsonArray();
        module.settings().forEach(setting -> values.add(setting.toJson()));
        json.add("settings", values);
        return json;
    }

    static JsonObject setting(Setting setting) {
        JsonObject json = new JsonObject();
        json.addProperty("id", setting.id());
        json.addProperty("name", setting.name());
        json.addProperty("type", setting.type());
        json.add("value", setting.value().get());
        json.addProperty("visible", setting.isVisible());
        json.addProperty("enabled", setting.isEnabled());
        if (setting.min() != null) json.addProperty("min", setting.min());
        if (setting.max() != null) json.addProperty("max", setting.max());
        if (setting.step() != null) json.addProperty("step", setting.step());
        if (!setting.options().isEmpty()) {
            JsonArray choices = new JsonArray();
            setting.options().forEach(choices::add);
            json.add("options", choices);
        }
        return json;
    }
}
