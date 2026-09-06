package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.service.profile.MojangProfileClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Combined OpenExpo AntiNick marker and DeNick resolver. */
public final class AntiNick {
    private static final BooleanSetting ENABLED = bool("antinick.enabled", false);
    private static final BooleanSetting MARK_NICK_UUID = bool("antinick.markNickUuid", true);
    private static final BooleanSetting RESOLVE_NAMES = bool("antinick.resolveNames", true);
    private static final StringSetting SUFFIX = string("antinick.suffix", "[Nick]");
    private static final IntSetting REFRESH_MS = integer("antinick.refreshMs", 5000, 500, 15000);
    private static final MojangProfileClient MOJANG = new MojangProfileClient();
    private static final Map<UUID, String> resolvedNames = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> retryAt = new ConcurrentHashMap<>();
    private static final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private static long nextRefreshAt;

    private AntiNick() { }
    public static void init() { EventBus.TICK_END.register("AntiNick.tickEnd", event -> tick(event.client())); }

    private static void tick(Minecraft client) {
        var connection = client == null ? null : client.getConnection();
        if (!ENABLED.get() || !RESOLVE_NAMES.get() || client == null || connection == null) return;
        long now = System.currentTimeMillis();
        if (now < nextRefreshAt) return;
        nextRefreshAt = now + REFRESH_MS.get();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            UUID uuid = info.getProfile().id();
            String visibleName = info.getProfile().name();
            if (uuid == null || visibleName == null || resolvedNames.containsKey(uuid)
                    || inFlight.contains(uuid) || retryAt.getOrDefault(uuid, 0L) > now) continue;
            inFlight.add(uuid);
            MOJANG.lookupByUuidAsync(uuid).whenComplete((profile, failure) -> client.execute(() -> {
                inFlight.remove(uuid);
                if (!ENABLED.get() || client.getConnection() != connection) return;
                if (failure != null) retryAt.put(uuid, System.currentTimeMillis() + 30_000L);
                else if (profile == null) retryAt.put(uuid, System.currentTimeMillis() + 60_000L);
                else {
                    retryAt.remove(uuid);
                    if (!profile.name().equalsIgnoreCase(visibleName)) {
                        resolvedNames.put(uuid, profile.name());
                        ClientChat.send(client, "§lAntiNick resolved §r" + visibleName + " §7-> §b" + profile.name());
                    } else resolvedNames.put(uuid, visibleName);
                }
            }));
        }
    }

    public static Component applyPlayerName(Player player, Component original) {
        return player == null ? original : decorate(player.getUUID(), player.getGameProfile().name(), original);
    }
    public static Component applyTabName(PlayerInfo info, Component original) {
        return info == null ? original : decorate(info.getProfile().id(), info.getProfile().name(), original);
    }
    private static Component decorate(UUID uuid, String visibleName, Component original) {
        if (!ENABLED.get() || uuid == null || original == null) return original;
        String plain = original.getString();
        Component result = original.copy();
        String resolved = resolvedNames.get(uuid);
        if (RESOLVE_NAMES.get() && resolved != null && visibleName != null
                && !resolved.equalsIgnoreCase(visibleName) && !plain.toLowerCase().contains(resolved.toLowerCase())) {
            result = result.copy().append(Component.literal(" (").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(resolved).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(")").withStyle(ChatFormatting.WHITE));
        }
        if (MARK_NICK_UUID.get() && uuid.version() == 1 && !plain.contains(SUFFIX.get())) {
            result = result.copy().append(Component.literal(" " + SUFFIX.get()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        }
        return result;
    }

    public static void shutdown() { MOJANG.close(); inFlight.clear(); }
    public static boolean isEnabled() { return ENABLED.get(); }
    public static String hudTag() { return "Duplicate"; }
    public static int setEnabled(Minecraft client, boolean value) { ENABLED.set(value); nextRefreshAt = 0L; ClientChat.send(client, "AntiNick " + (value ? "enabled" : "disabled") + "."); return 1; }
    public static int setMarkNickUuid(Minecraft ignoredClient, boolean value) { MARK_NICK_UUID.set(value); return 1; }
    public static int setResolveNames(Minecraft ignoredClient, boolean value) { RESOLVE_NAMES.set(value); return 1; }
    public static int setSuffix(Minecraft ignoredClient, String value) { SUFFIX.set(value); return 1; }
    public static int setRefreshMs(Minecraft ignoredClient, int value) { REFRESH_MS.set(value); nextRefreshAt = 0L; return 1; }
    private static BooleanSetting bool(String name, boolean value) { return new BooleanSetting.Builder().name(name).defaultValue(value).build(); }
    private static StringSetting string(String name, String value) { return new StringSetting.Builder().name(name).defaultValue(value).build(); }
    private static IntSetting integer(String name, int value, int min, int max) { return new IntSetting.Builder().name(name).defaultValue(value).range(min, max).build(); }
}
