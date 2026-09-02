package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.MaterialAssetGroup;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies Hoplite armor-trim cosmetics to render-only ItemStack copies. */
public final class TrimChanger {
    private static final BooleanSetting ENABLED = new BooleanSetting.Builder()
            .name("trimchanger.enabled")
            .defaultValue(false)
            .build();
    private static final BooleanSetting OVERRIDE = new BooleanSetting.Builder()
            .name("trimchanger.override")
            .defaultValue(false)
            .build();
    private static final ModeSetting<Pattern> PATTERN = buildPatternSetting();
    private static final ModeSetting<Material> MATERIAL = buildMaterialSetting();
    private static final ThreadLocal<AvatarRenderState> CURRENT_AVATAR = new ThreadLocal<>();
    private static final Map<Pattern, Holder<TrimPattern>> PATTERN_HOLDERS = buildPatternHolders();
    private static final Map<Material, Holder<TrimMaterial>> MATERIAL_HOLDERS = buildMaterialHolders();

    private TrimChanger() {
    }

    public static void init() {
        if (ENABLED.get()) TrimResourcePack.installAndLoad(Minecraft.getInstance());
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static List<String> patternOptions() {
        return PATTERN.optionIds();
    }

    public static List<String> materialOptions() {
        return MATERIAL.optionIds();
    }

    public static String patternId() {
        return PATTERN.serialized();
    }

    public static String materialId() {
        return MATERIAL.serialized();
    }

    public static boolean overrideOtherTrims() {
        return OVERRIDE.get();
    }

    public static String hudTag() {
        return title(PATTERN.serialized());
    }

    public static void beginAvatar(AvatarRenderState state) {
        if (state == null) CURRENT_AVATAR.remove();
        else CURRENT_AVATAR.set(state);
    }

    public static void endAvatar() {
        CURRENT_AVATAR.remove();
    }

    /** Returns a detached visual copy; the network-owned stack is never mutated. */
    public static ItemStack apply(ItemStack original) {
        if (!ENABLED.get() || original == null || original.isEmpty()) {
            return original;
        }

        AvatarRenderState state = CURRENT_AVATAR.get();
        if (state == null) return original;
        if (!isLocalAvatar(state)) {
            if (!OVERRIDE.get() || !original.has(DataComponents.TRIM)) return original;
            ItemStack withoutTrim = original.copy();
            withoutTrim.remove(DataComponents.TRIM);
            return withoutTrim;
        }

        Holder<TrimPattern> pattern = PATTERN_HOLDERS.get(PATTERN.get());
        Holder<TrimMaterial> material = MATERIAL_HOLDERS.get(MATERIAL.get());
        if (pattern == null || material == null) return original;

        ItemStack visual = original.copy();
        visual.set(DataComponents.TRIM, new ArmorTrim(material, pattern));
        return visual;
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        ENABLED.set(enabled);
        if (enabled) TrimResourcePack.installAndLoad(client);
        else CURRENT_AVATAR.remove();
        ClientChat.send(client, "TrimChanger " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setPattern(Minecraft client, String value) {
        PATTERN.deserialize(value);
        return 1;
    }

    public static int setMaterial(Minecraft client, String value) {
        MATERIAL.deserialize(value);
        return 1;
    }

    public static int setOverrideOtherTrims(Minecraft client, boolean value) {
        OVERRIDE.set(value);
        return 1;
    }

    private static boolean isLocalAvatar(AvatarRenderState state) {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && state.id == client.player.getId();
    }

    private static ModeSetting<Pattern> buildPatternSetting() {
        ModeSetting.Builder<Pattern> builder = new ModeSetting.Builder<Pattern>()
                .name("trimchanger.pattern")
                .defaultValue(Pattern.FROST);
        for (Pattern pattern : Pattern.values()) builder.option(pattern, pattern.id);
        return builder.build();
    }

    private static ModeSetting<Material> buildMaterialSetting() {
        ModeSetting.Builder<Material> builder = new ModeSetting.Builder<Material>()
                .name("trimchanger.material")
                .defaultValue(Material.DIAMOND);
        for (Material material : Material.values()) builder.option(material, material.id);
        return builder.build();
    }

    private static Map<Pattern, Holder<TrimPattern>> buildPatternHolders() {
        EnumMap<Pattern, Holder<TrimPattern>> result = new EnumMap<>(Pattern.class);
        for (Pattern pattern : Pattern.values()) {
            result.put(pattern, Holder.direct(new TrimPattern(
                    Identifier.fromNamespaceAndPath("civilization", pattern.id + "_trim"),
                    Component.literal(title(pattern.id)),
                    false)));
        }
        return Map.copyOf(result);
    }

    private static Map<Material, Holder<TrimMaterial>> buildMaterialHolders() {
        EnumMap<Material, Holder<TrimMaterial>> result = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            result.put(material, Holder.direct(new TrimMaterial(
                    material.assets,
                    Component.literal(title(material.id)))));
        }
        return Map.copyOf(result);
    }

    private static String title(String id) {
        StringBuilder result = new StringBuilder();
        for (String part : id.split("_")) {
            if (!result.isEmpty()) result.append(' ');
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return result.toString();
    }

    private enum Pattern {
        AQUATIC("aquatic"),
        DRAGON("dragon"),
        EASTER("easter"),
        END("end"),
        FLAME("flame"),
        FROST("frost"),
        GRAPHITE("graphite"),
        HEART("heart"),
        INFECTED("infected"),
        IRONCLAD("ironclad"),
        LIGHTNING("lightning"),
        MUMMY("mummy"),
        NECROTIC("necrotic"),
        NETHER_HEART("nether_heart"),
        OCEAN("ocean"),
        OMINOUS("ominous"),
        PUMPKIN("pumpkin"),
        RADIOACTIVE("radioactive"),
        REDSTONE("redstone"),
        RUNIC("runic"),
        SPIRAL("spiral"),
        WARDEN("warden"),
        WITHER("wither"),
        ZOMBIE("zombie");

        private final String id;

        Pattern(String id) {
            this.id = id;
        }
    }

    private enum Material {
        AMETHYST("amethyst", MaterialAssetGroup.AMETHYST),
        COPPER("copper", MaterialAssetGroup.COPPER),
        DIAMOND("diamond", MaterialAssetGroup.DIAMOND),
        EMERALD("emerald", MaterialAssetGroup.EMERALD),
        GOLD("gold", MaterialAssetGroup.GOLD),
        IRON("iron", MaterialAssetGroup.IRON),
        LAPIS("lapis", MaterialAssetGroup.LAPIS),
        NETHERITE("netherite", MaterialAssetGroup.NETHERITE),
        QUARTZ("quartz", MaterialAssetGroup.QUARTZ),
        REDSTONE("redstone", MaterialAssetGroup.REDSTONE),
        RESIN("resin", MaterialAssetGroup.RESIN);

        private final String id;
        private final MaterialAssetGroup assets;

        Material(String id, MaterialAssetGroup assets) {
            this.id = id;
            this.assets = assets;
        }
    }
}
