package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.utils.registry.RegistryLists;
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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UhcFinder {
    private static final float BOX_ALPHA = 0.35f;
    private static final float OFFLINE_PLAYER_ALPHA = 0.85f;
    private static final Map<EntityType<?>, Integer> TARGETS = loadTargets();

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("uhcfinder.enabled").defaultValue(true).build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("uhcfinder.range")
                    .defaultValue(256.0D)
                    .range(8.0D, 1024.0D)
                    .build();

    private UhcFinder() {}

    public static void init() {
        EventBus.WORLD_RENDER.register("UhcFinder.worldRender", UhcFinder::render);
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "UhcFinder: "
                        + statusText()
                        + ", range: "
                        + format(RANGE.get())
                        + ". Usage: .moons uhcfinder <enable|disable|range 8-1024>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        ClientChat.send(
                client, "UhcFinder " + statusText() + ". Range: " + format(RANGE.get()) + ".");
        return 1;
    }

    public static int setRange(Minecraft client, double newRange) {
        RANGE.set(newRange);
        ClientChat.send(client, "UhcFinder range set to " + format(RANGE.get()) + ".");
        return 1;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static double range() {
        return RANGE.get();
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
                result.add(RegistryLists.entry(id, color));
            }
        }
        return result;
    }

    public static JsonArray selectedTargets() {
        JsonArray result = new JsonArray();
        TARGETS.forEach(
                (type, color) ->
                        result.add(
                                RegistryLists.entry(
                                        BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
                                        String.format("#%06x", color))));
        return result;
    }

    public static void setTargets(Minecraft client, JsonElement value) {
        if (!RegistryLists.valid("entity_list", value))
            throw new IllegalArgumentException("Invalid entity list");
        TARGETS.clear();
        readTargets(value.getAsJsonArray(), TARGETS);
        Settings.setString("uhcfinder.targets", selectedTargets().toString());
    }

    private static Map<EntityType<?>, Integer> loadTargets() {
        JsonArray entries = null;
        String saved = Settings.getString("uhcfinder.targets", "");
        if (!saved.isEmpty()) {
            try {
                JsonElement parsed = JsonParser.parseString(saved);
                if (RegistryLists.valid("entity_list", parsed)) entries = parsed.getAsJsonArray();
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

    private static void render(WorldRenderEvent context) {
        if (!ENABLED.get()) {
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
        float tickDelta = context.tickDelta();

        double maxDistanceSquared = RANGE.get() * RANGE.get();
        List<Entity> targets = new ArrayList<>();

        for (Entity entity : currentLevel.entitiesForRendering()) {
            if (shouldRenderTarget(client, entity, maxDistanceSquared)) {
                if (entity instanceof LivingEntity livingEntity)
                    OfflinePlayerDetect.notifyIfNeeded(client, livingEntity);
                targets.add(entity);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        targets.sort(Comparator.comparingDouble(target -> currentPlayer.distanceToSqr(target)));

        List<WorldOverlayRenderer.ColoredBox> boxes = new ArrayList<>(targets.size());
        for (Entity target : targets) {
            boxes.add(createEntityBox(target, tickDelta));
        }

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        WorldOverlayRenderer.render(client, matrices, boxes, "uhcfinder entity boxes");
        matrices.popPose();
    }

    private static boolean shouldRenderTarget(
            Minecraft client, Entity entity, double maxDistanceSquared) {
        return entity != client.player
                && !entity.isRemoved()
                && entity.isAlive()
                && !entity.isSpectator()
                && client.player.distanceToSqr(entity) <= maxDistanceSquared
                && isUhcFinderTarget(entity);
    }

    private static boolean isUhcFinderTarget(Entity entity) {
        return TARGETS.containsKey(entity.getType());
    }

    private static WorldOverlayRenderer.ColoredBox createEntityBox(Entity entity, float tickDelta) {
        Vec3 pos = interpolatedPosition(entity, tickDelta);
        float[] color = colorFor(entity);
        boolean offlinePlayer =
                entity instanceof LivingEntity living
                        && OfflinePlayerDetect.isOfflinePlayerZombie(living);

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
                color[0],
                color[1],
                color[2],
                offlinePlayer ? OFFLINE_PLAYER_ALPHA : BOX_ALPHA);
    }

    private static Vec3 interpolatedPosition(Entity entity, float tickDelta) {
        return new Vec3(
                Mth.lerp((double) tickDelta, entity.xo, entity.getX()),
                Mth.lerp((double) tickDelta, entity.yo, entity.getY()),
                Mth.lerp((double) tickDelta, entity.zo, entity.getZ()));
    }

    private static float[] colorFor(Entity entity) {
        int color =
                entity instanceof LivingEntity living
                                && OfflinePlayerDetect.isOfflinePlayerZombie(living)
                        ? Settings.getInt("uhcfinder.offlineColor", 0xff00ff)
                        : TARGETS.get(entity.getType());
        return rgb(color >> 16 & 255, color >> 8 & 255, color & 255);
    }

    private static float[] rgb(int red, int green, int blue) {
        return new float[] {red / 255.0f, green / 255.0f, blue / 255.0f};
    }

    private static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    private static String format(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }
}
