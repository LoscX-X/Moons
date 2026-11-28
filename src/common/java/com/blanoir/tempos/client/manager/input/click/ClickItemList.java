package com.blanoir.tempos.client.manager.input.click;

import com.blanoir.tempos.client.config.settings.StringSetting;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/** The existing item-list editor stores explicit items; unedited defaults follow tool families. */
public final class ClickItemList {
    private final StringSetting storage;

    public enum Family {
        SWORDS,
        TOOLS,
        BLOCKS
    }

    private final Family family;
    private String cachedRaw;
    private Set<Identifier> cached = Set.of();

    public ClickItemList(String key, Family family) {
        storage = new StringSetting.Builder().name(key).defaultValue("").build();
        this.family = family;
    }

    public boolean allows(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String raw = storage.get();
        if (raw.isEmpty()) return defaultItem(stack);
        if (!raw.equals(cachedRaw)) {
            var selected = new HashSet<Identifier>();
            try {
                JsonElement value = JsonParser.parseString(raw);
                if (!SettingFactory.valid("item_list", value)) return false;
                for (JsonElement entry : value.getAsJsonArray())
                    selected.add(Identifier.parse(entry.getAsJsonObject().get("id").getAsString()));
            } catch (RuntimeException invalid) {
                return false;
            }
            cached = Set.copyOf(selected);
            cachedRaw = raw;
        }
        return cached.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private boolean defaultItem(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return switch (family) {
            case TOOLS ->
                    stack.is(ItemTags.PICKAXES)
                            || stack.is(ItemTags.SHOVELS)
                            || path.endsWith("_pickaxe")
                            || path.endsWith("_shovel");
            case SWORDS -> stack.is(ItemTags.SWORDS) || path.endsWith("_sword");
            case BLOCKS -> stack.getItem() instanceof BlockItem;
        };
    }

    private JsonArray defaults() {
        JsonArray ids = new JsonArray();
        // Descriptors are also built before data-pack components bind; do not create ItemStacks.
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            String path = id.getPath();
            boolean included =
                    switch (family) {
                        case SWORDS -> path.endsWith("_sword");
                        case TOOLS -> path.endsWith("_pickaxe") || path.endsWith("_shovel");
                        case BLOCKS -> item instanceof BlockItem;
                    };
            if (included) ids.add(SettingFactory.entry(id.toString(), null));
        }
        return ids;
    }

    public Setting setting(String id, String label) {
        return SettingFactory.registryList(
                id,
                label,
                "item",
                () -> {
                    String raw = storage.get();
                    if (raw.isEmpty()) return defaults();
                    try {
                        return JsonParser.parseString(raw);
                    } catch (RuntimeException invalid) {
                        return new JsonArray();
                    }
                },
                (client, value) -> {
                    if (!SettingFactory.valid("item_list", value))
                        throw new IllegalArgumentException("Invalid item whitelist");
                    storage.set(value.toString());
                    cachedRaw = null;
                },
                defaults());
    }
}
