package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.render.LegacyPoseStack;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.render.world.OverlayFrustum;
import com.blanoir.moons.client.utils.registry.RegistryLists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UhcFinder {
    private static final float BOX_ALPHA = 0.35f;
    private static final float OFFLINE_PLAYER_ALPHA = 0.85f;
    private static final Map<String, Integer> TARGETS = loadTargets();
    private static final OverlayFrustum VIEW = new OverlayFrustum();

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
        for (String type : EntityList.getEntityNameList()) {
            String id = "minecraft:" + type;
            if (EntityList.getClassFromID(EntityList.getIDFromString(type)) != null
                    && IMob.class.isAssignableFrom(
                            EntityList.getClassFromID(EntityList.getIDFromString(type)))) {
                String color =
                        switch (id.toLowerCase(java.util.Locale.ROOT)) {
                            case "minecraft:enderman" -> "#8f00e2";
                            case "minecraft:blaze" -> "#ef8002";
                            case "minecraft:lavaslime", "minecraft:magma_cube" -> "#b11635";
                            case "minecraft:slime" -> "#29ff00";
                            case "minecraft:creeper" -> "#1d9c07";
                            case "minecraft:zombie",
                                    "minecraft:pigzombie",
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
                                        "minecraft:" + type, String.format("#%06x", color))));
        return result;
    }

    public static void setTargets(Minecraft client, JsonElement value) {
        if (!RegistryLists.valid("entity_list", value))
            throw new IllegalArgumentException("Invalid entity list");
        TARGETS.clear();
        readTargets(value.getAsJsonArray(), TARGETS);
        Settings.setString("uhcfinder.targets", selectedTargets().toString());
    }

    private static Map<String, Integer> loadTargets() {
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
        Map<String, Integer> result = new LinkedHashMap<>();
        readTargets(entries == null ? defaultTargets() : entries, result);
        return result;
    }

    private static void readTargets(JsonArray entries, Map<String, Integer> result) {
        for (JsonElement value : entries) {
            var entry = value.getAsJsonObject();
            if (!entry.get("enabled").getAsBoolean()) continue;
            String type = entry.get("id").getAsString().replace("minecraft:", "");
            result.put(type, Integer.parseInt(entry.get("color").getAsString().substring(1), 16));
        }
    }

    private static void render(WorldRenderEvent context) {
        if (!ENABLED.get()) {
            return;
        }

        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        var currentLevel = client == null ? null : client.theWorld;
        if (currentLevel == null
                || currentPlayer == null
                || MinecraftClientAccess.isHudHidden(client)) {
            return;
        }

        LegacyPoseStack matrices = context.poseStack();
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        VIEW.update(MinecraftClientAccess.camera(client));
        float tickDelta = context.tickDelta();

        double maxDistanceSquared = RANGE.get() * RANGE.get();
        List<Entity> targets = new ArrayList<>();

        for (Entity entity : currentLevel.loadedEntityList) {
            if (shouldRenderTarget(client, entity, maxDistanceSquared)) {
                if (entity instanceof EntityLivingBase livingEntity)
                    OfflinePlayerDetect.notifyIfNeeded(client, livingEntity);
                targets.add(entity);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        targets.sort(
                Comparator.comparingDouble(target -> currentPlayer.getDistanceSqToEntity(target)));

        List<WorldOverlayRenderer.ColoredBox> boxes = new ArrayList<>(targets.size());
        for (Entity target : targets) {
            var box = createEntityBox(target, tickDelta);
            if (VIEW.isVisible(
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))
                boxes.add(box);
        }

        matrices.pushPose();
        try {
            matrices.translate(-camera.xCoord, -camera.yCoord, -camera.zCoord);
            WorldOverlayRenderer.renderStyled(client, matrices, boxes, "uhcfinder entity boxes");
        } finally {
            matrices.popPose();
        }
    }

    private static boolean shouldRenderTarget(
            Minecraft client, Entity entity, double maxDistanceSquared) {
        return entity != client.thePlayer
                && !entity.isDead
                && entity.isEntityAlive()
                && !(entity instanceof EntityPlayer player && player.isSpectator())
                && client.thePlayer.getDistanceSqToEntity(entity) <= maxDistanceSquared
                && isUhcFinderTarget(entity);
    }

    private static boolean isUhcFinderTarget(Entity entity) {
        return isInvisiblePlayer(entity) || TARGETS.containsKey(EntityList.getEntityString(entity));
    }

    private static boolean isInvisiblePlayer(Entity entity) {
        return entity instanceof EntityPlayer && entity.isInvisible();
    }

    private static WorldOverlayRenderer.ColoredBox createEntityBox(Entity entity, float tickDelta) {
        Vec3 pos = interpolatedPosition(entity, tickDelta);
        int color = colorFor(entity);
        boolean offlinePlayer =
                entity instanceof EntityLivingBase living
                        && OfflinePlayerDetect.isOfflinePlayerZombie(living);

        net.minecraft.util.AxisAlignedBB box =
                entity.getEntityBoundingBox()
                        .offset(
                                pos.xCoord - entity.posX,
                                pos.yCoord - entity.posY,
                                pos.zCoord - entity.posZ);

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
                Mth.lerp((double) tickDelta, entity.lastTickPosX, entity.posX),
                Mth.lerp((double) tickDelta, entity.lastTickPosY, entity.posY),
                Mth.lerp((double) tickDelta, entity.lastTickPosZ, entity.posZ));
    }

    private static int colorFor(Entity entity) {
        if (isInvisiblePlayer(entity)) return 0xffffff;
        return entity instanceof EntityLivingBase living
                        && OfflinePlayerDetect.isOfflinePlayerZombie(living)
                ? Settings.getInt("uhcfinder.offlineColor", 0xff00ff)
                : TARGETS.get(EntityList.getEntityString(entity));
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
