package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.MaterialAssetGroup;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies Hoplite armor-trim cosmetics to render-only ItemStack copies. */
public final class Trim {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("trim.enabled").defaultValue(false).build();
    private static final BooleanSetting OVERRIDE =
            new BooleanSetting.Builder().name("trim.override").defaultValue(false).build();
    private static final ModeSetting<Pattern> PATTERN = buildPatternSetting();
    private static final ModeSetting<Material> MATERIAL = buildMaterialSetting();
    private static final ThreadLocal<AvatarRenderState> CURRENT_AVATAR = new ThreadLocal<>();
    private static final Map<Pattern, Holder<TrimPattern>> PATTERN_HOLDERS = buildPatternHolders();
    private static final Map<Material, Holder<TrimMaterial>> MATERIAL_HOLDERS =
            buildMaterialHolders();

    private Trim() {}

    public static void close() {
        CURRENT_AVATAR.remove();
        TrimTextures.close(Minecraft.getInstance());
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
        if (!enabled) CURRENT_AVATAR.remove();
        ClientChat.send(client, "Trim " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    /** Called at the vanilla trim pass, preserving its model, pose and draw order. */
    @SuppressWarnings("unchecked")
    public static boolean render(Object[] args) {
        if (!ENABLED.get() || args.length != 10) return false;
        AvatarRenderState avatar = CURRENT_AVATAR.get();
        if (avatar == null || !isLocalAvatar(avatar)) return false;
        EquipmentClientInfo.LayerType layer = (EquipmentClientInfo.LayerType) args[0];
        if (layer != EquipmentClientInfo.LayerType.HUMANOID
                && layer != EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS) return false;
        ResourceKey<EquipmentAsset> asset = (ResourceKey<EquipmentAsset>) args[1];
        ItemStack stack = (ItemStack) args[4];
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim == null || trim.pattern() != PATTERN_HOLDERS.get(PATTERN.get())) return false;
        String palette = trim.material().value().assets().assetId(asset).suffix();
        Identifier texture =
                TrimTextures.texture(
                        Minecraft.getInstance(),
                        PATTERN.serialized(),
                        layer == EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS,
                        palette);
        // A failed upload must not fall through to a missing custom atlas sprite.
        if (texture == null) return true;
        Model<Object> model = (Model<Object>) args[2];
        SubmitNodeCollector collector = (SubmitNodeCollector) args[6];
        collector
                .order((Integer) args[9])
                .submitModel(
                        model,
                        args[3],
                        (PoseStack) args[5],
                        Chams.remapIfNeeded(RenderTypes.armorCutoutNoCull(texture)),
                        (Integer) args[7],
                        OverlayTexture.NO_OVERLAY,
                        -1,
                        null,
                        (Integer) args[8],
                        null);
        return true;
    }

    public static int setPattern(Minecraft ignoredClient, String value) {
        PATTERN.deserialize(value);
        return 1;
    }

    public static int setMaterial(Minecraft ignoredClient, String value) {
        MATERIAL.deserialize(value);
        return 1;
    }

    public static int setOverrideOtherTrims(Minecraft ignoredClient, boolean value) {
        OVERRIDE.set(value);
        return 1;
    }

    private static boolean isLocalAvatar(AvatarRenderState state) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        return currentPlayer != null && state.id == currentPlayer.getId();
    }

    private static ModeSetting<Pattern> buildPatternSetting() {
        ModeSetting.Builder<Pattern> builder =
                new ModeSetting.Builder<Pattern>().name("trim.pattern").defaultValue(Pattern.FROST);
        for (Pattern pattern : Pattern.values()) builder.option(pattern, pattern.id);
        return builder.build();
    }

    private static ModeSetting<Material> buildMaterialSetting() {
        ModeSetting.Builder<Material> builder =
                new ModeSetting.Builder<Material>()
                        .name("trim.material")
                        .defaultValue(Material.DIAMOND);
        for (Material material : Material.values()) builder.option(material, material.id);
        return builder.build();
    }

    private static Map<Pattern, Holder<TrimPattern>> buildPatternHolders() {
        EnumMap<Pattern, Holder<TrimPattern>> result = new EnumMap<>(Pattern.class);
        for (Pattern pattern : Pattern.values()) {
            result.put(
                    pattern,
                    Holder.direct(
                            new TrimPattern(
                                    Identifier.fromNamespaceAndPath(
                                            "civilization", pattern.id + "_trim"),
                                    Component.literal(title(pattern.id)),
                                    false)));
        }
        return Map.copyOf(result);
    }

    private static Map<Material, Holder<TrimMaterial>> buildMaterialHolders() {
        EnumMap<Material, Holder<TrimMaterial>> result = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            result.put(
                    material,
                    Holder.direct(
                            new TrimMaterial(
                                    material.assets, Component.literal(title(material.id)))));
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
