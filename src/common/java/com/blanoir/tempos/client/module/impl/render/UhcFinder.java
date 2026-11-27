package com.blanoir.tempos.client.module.impl.render;

import static com.blanoir.tempos.client.module.framework.SettingFactory.color;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.frame.WorldRenderEvent;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.blanoir.tempos.client.render.WorldOverlayRenderer;
import com.blanoir.tempos.client.render.world.OverlayFrustum;
import com.blanoir.tempos.client.utils.text.NumberText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Highlights selected entities within the configured range.")
public final class UhcFinder extends ClientFeature {
    private static final String TARGETS_KEY = "uhcfinder.targets";
    private static final String OFFLINE_COLOR_KEY = "uhcfinder.offlineColor";
    private static final int DEFAULT_OFFLINE_COLOR = 0xff00ff;
    public static final UhcFinder INSTANCE = new UhcFinder();

    private static final float BOX_ALPHA = 0.35f;
    private static final float OFFLINE_PLAYER_ALPHA = 0.85f;
    private final Map<EntityType<?>, Integer> targets = loadTargets();
    private final OverlayFrustum view = new OverlayFrustum();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("uhcfinder.enabled").defaultValue(true).build();

    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("uhcfinder.range")
                    .defaultValue(256.0D)
                    .range(8.0D, 1024.0D)
                    .build();

    private UhcFinder() {
        super("uhcfinder", "UhcFinder");
    }

    @Override
    public void registerEvents() {
        EventBus.WORLD_RENDER.register("UhcFinder.worldRender", this::render);
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "UhcFinder: "
                        + statusText()
                        + ", range: "
                        + NumberText.compactDouble(this.range.get())
                        + ". Usage: .tempos uhcfinder <enable|disable|range 8-1024>");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        ClientChat.send(
                client,
                "UhcFinder "
                        + statusText()
                        + ". Range: "
                        + NumberText.compactDouble(this.range.get())
                        + ".");
        return 1;
    }

    public int setRange(Minecraft client, double newRange) {
        this.range.set(newRange);
        ClientChat.send(
                client,
                "UhcFinder range set to " + NumberText.compactDouble(this.range.get()) + ".");
        return 1;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public double range() {
        return this.range.get();
    }

    public static JsonArray defaultTargets() {
        JsonArray result = new JsonArray();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if (type.getCategory() == MobCategory.MONSTER || id.equals("minecraft:glow_squid")) {
                String color =
                        switch (id) {
                            case "minecraft:enderman" -> "#8f00e2";
                            case "minecraft:blaze" -> "#ef8002";
                            case "minecraft:magma_cube" -> "#b11635";
                            case "minecraft:slime" -> "#29ff00";
                            case "minecraft:creeper" -> "#1d9c07";
                            case "minecraft:zombie",
                                    "minecraft:husk",
                                    "minecraft:drowned",
                                    "minecraft:zombie_villager",
                                    "minecraft:zombified_piglin" ->
                                    "#ff0000";
                            case "minecraft:ghast" -> "#ffbebe";
                            case "minecraft:glow_squid" -> "#00ffdc";
                            default -> "#ffd700";
                        };
                result.add(SettingFactory.entry(id, color));
            }
        }
        return result;
    }

    public JsonArray selectedTargets() {
        JsonArray result = new JsonArray();
        this.targets.forEach(
                (type, color) ->
                        result.add(
                                SettingFactory.entry(
                                        BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
                                        String.format("#%06x", color))));
        return result;
    }

    public void setTargets(Minecraft client, JsonElement value) {
        if (!SettingFactory.valid("entity_list", value))
            throw new IllegalArgumentException("Invalid entity list");
        this.targets.clear();
        readTargets(value.getAsJsonArray(), this.targets);
        Settings.setString(TARGETS_KEY, selectedTargets().toString());
    }

    private static Map<EntityType<?>, Integer> loadTargets() {
        JsonArray entries = null;
        String saved = Settings.getString(TARGETS_KEY, "");
        if (!saved.isEmpty()) {
            try {
                JsonElement parsed = JsonParser.parseString(saved);
                if (SettingFactory.valid("entity_list", parsed)) entries = parsed.getAsJsonArray();
            } catch (com.google.gson.JsonParseException ignored) {
                // Malformed saved values use the declared defaults.
            }
        }
        Map<EntityType<?>, Integer> result = new LinkedHashMap<>();
        readTargets(entries == null ? defaultTargets() : entries, result);
        return result;
    }

    private static void readTargets(JsonArray entries, Map<EntityType<?>, Integer> result) {
        for (JsonElement value : entries) {
            var entry = value.getAsJsonObject();
            if (!entry.get("enabled").getAsBoolean()) continue;
            EntityType<?> type =
                    BuiltInRegistries.ENTITY_TYPE
                            .getOptional(Identifier.parse(entry.get("id").getAsString()))
                            .orElseThrow();
            result.put(type, Integer.parseInt(entry.get("color").getAsString().substring(1), 16));
        }
    }

    private void render(WorldRenderEvent context) {
        if (!this.enabled.get()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        var currentLevel = client == null ? null : client.level;
        if (currentLevel == null
                || currentPlayer == null
                || MinecraftClientAccess.isHudHidden(client)) {
            return;
        }

        PoseStack matrices = context.poseStack();
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        this.view.update(MinecraftClientAccess.camera(client));
        float tickDelta = context.tickDelta();

        double maxDistanceSquared = this.range.get() * this.range.get();
        List<Entity> targets = new ArrayList<>();

        for (Entity entity : currentLevel.entitiesForRendering()) {
            if (shouldRenderTarget(client, entity, maxDistanceSquared)) {
                if (entity instanceof LivingEntity livingEntity)
                    OfflinePlayerDetect.INSTANCE.notifyIfNeeded(client, livingEntity);
                targets.add(entity);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        targets.sort(Comparator.comparingDouble(target -> currentPlayer.distanceToSqr(target)));

        List<WorldOverlayRenderer.ColoredBox> boxes = new ArrayList<>(targets.size());
        for (Entity target : targets) {
            var box = createEntityBox(target, tickDelta);
            if (this.view.isVisible(
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))
                boxes.add(box);
        }

        matrices.pushPose();
        try {
            matrices.translate(-camera.x, -camera.y, -camera.z);
            WorldOverlayRenderer.renderStyled(client, matrices, boxes, "uhcfinder entity boxes");
        } finally {
            matrices.popPose();
        }
    }

    private boolean shouldRenderTarget(Minecraft client, Entity entity, double maxDistanceSquared) {
        return entity != client.player
                && !entity.isRemoved()
                && entity.isAlive()
                && !entity.isSpectator()
                && client.player.distanceToSqr(entity) <= maxDistanceSquared
                && isUhcFinderTarget(entity);
    }

    private boolean isUhcFinderTarget(Entity entity) {
        return isInvisiblePlayer(entity) || this.targets.containsKey(entity.getType());
    }

    private static boolean isInvisiblePlayer(Entity entity) {
        return entity instanceof Player && entity.isInvisible();
    }

    private WorldOverlayRenderer.ColoredBox createEntityBox(Entity entity, float tickDelta) {
        Vec3 pos = interpolatedPosition(entity, tickDelta);
        int color = colorFor(entity);
        boolean offlinePlayer =
                entity instanceof LivingEntity living
                        && OfflinePlayerDetect.INSTANCE.isOfflinePlayerZombie(living);

        net.minecraft.world.phys.AABB box =
                entity.getBoundingBox()
                        .move(pos.x - entity.getX(), pos.y - entity.getY(), pos.z - entity.getZ());

        return new WorldOverlayRenderer.ColoredBox(
                (float) box.minX,
                (float) box.minY,
                (float) box.minZ,
                (float) box.maxX,
                (float) box.maxY,
                (float) box.maxZ,
                (color >> 16 & 255) / 255.0f,
                (color >> 8 & 255) / 255.0f,
                (color & 255) / 255.0f,
                offlinePlayer ? OFFLINE_PLAYER_ALPHA : BOX_ALPHA);
    }

    private static Vec3 interpolatedPosition(Entity entity, float tickDelta) {
        return new Vec3(
                Mth.lerp((double) tickDelta, entity.xo, entity.getX()),
                Mth.lerp((double) tickDelta, entity.yo, entity.getY()),
                Mth.lerp((double) tickDelta, entity.zo, entity.getZ()));
    }

    private static int offlineColor() {
        return Settings.getInt(OFFLINE_COLOR_KEY, DEFAULT_OFFLINE_COLOR);
    }

    private int colorFor(Entity entity) {
        if (isInvisiblePlayer(entity)) return 0xffffff;
        return entity instanceof LivingEntity living
                        && OfflinePlayerDetect.INSTANCE.isOfflinePlayerZombie(living)
                ? offlineColor()
                : this.targets.get(entity.getType());
    }

    private String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            range.describeStored("range", "Range", 8, this::setRange),
            SettingFactory.registryList(
                    "targets",
                    "Entities",
                    "entity",
                    this::selectedTargets,
                    this::setTargets,
                    UhcFinder.defaultTargets()),
            color(
                            "offline_color",
                            "Offline player color",
                            () -> String.format("#%06x", offlineColor()),
                            (client, rgb) ->
                                    Settings.setInt(
                                            OFFLINE_COLOR_KEY, rgb[0] << 16 | rgb[1] << 8 | rgb[2]))
                    .withDefault(String.format("#%06x", DEFAULT_OFFLINE_COLOR))
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
