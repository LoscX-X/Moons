package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.impl.render.NicknameShuffle;
import com.blanoir.moons.client.service.MojangProfileService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Combined nickname marker and original-name resolver. */
public final class AntiNick {
    private static final BooleanSetting ENABLED = bool("antinick.enabled", false);
    private static final BooleanSetting MARK_NICK_UUID = bool("antinick.markNickUuid", true);
    private static final BooleanSetting IGNORE_SELF = bool("antinick.ignoreSelf", true);
    private static final BooleanSetting RESOLVE_NAMES = bool("antinick.resolveNames", true);
    private static final StringSetting SUFFIX = string("antinick.suffix", "[Nick]");
    private static final IntSetting REFRESH_MS = integer("antinick.refreshMs", 5000, 500, 15000);
    private static final MojangProfileService MOJANG = new MojangProfileService();
    private static final Map<UUID, String> resolvedNames = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> retryAt = new ConcurrentHashMap<>();
    private static final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private static long nextRefreshAt;

    private AntiNick() {}

    public static void init() {
        com.blanoir.moons.api.ScopedResources.own(MOJANG);
        EventBus.TICK_END.register("AntiNick.tickEnd", event -> tick(event.client()));
    }

    private static void tick(Minecraft client) {
        var connection = client == null ? null : client.getNetHandler();
        if (!ENABLED.get()
                || !RESOLVE_NAMES.get()
                || NicknameShuffle.isEnabled()
                || client == null
                || connection == null) return;
        long now = System.currentTimeMillis();
        if (now < nextRefreshAt) return;
        nextRefreshAt = now + REFRESH_MS.get();
        var domain =
                new com.blanoir.moons.client.threads.ThreadBoundExecutor(
                        "AntiNick result",
                        client::isCallingFromMinecraftThread,
                        client::addScheduledTask);
        var lifetime = MOJANG.lifetime();
        for (NetworkPlayerInfo info : connection.getPlayerInfoMap()) {
            UUID uuid = info.getGameProfile().getId();
            String visibleName = info.getGameProfile().getName();
            if (uuid == null
                    || ignored(client, uuid)
                    || visibleName == null
                    || resolvedNames.containsKey(uuid)
                    || inFlight.contains(uuid)
                    || retryAt.getOrDefault(uuid, 0L) > now) continue;
            inFlight.add(uuid);
            MOJANG.lookupByUuidAsync(uuid)
                    .whenComplete(
                            (profile, failure) ->
                                    domain.execute(
                                            lifetime,
                                            () -> {
                                                inFlight.remove(uuid);
                                                if (!ENABLED.get()
                                                        || ignored(client, uuid)
                                                        || NicknameShuffle.isEnabled()
                                                        || client.getNetHandler() != connection)
                                                    return;
                                                if (failure != null) {
                                                    retryAt.put(
                                                            uuid,
                                                            System.currentTimeMillis() + 30_000L);
                                                } else if (profile == null) {
                                                    retryAt.put(
                                                            uuid,
                                                            System.currentTimeMillis() + 60_000L);
                                                } else {
                                                    retryAt.remove(uuid);
                                                    if (!profile.name()
                                                            .equalsIgnoreCase(visibleName)) {
                                                        resolvedNames.put(uuid, profile.name());
                                                        ClientChat.send(
                                                                client,
                                                                "§lAntiNick resolved §r"
                                                                        + visibleName
                                                                        + " §7-> §b"
                                                                        + profile.name());
                                                    } else {
                                                        resolvedNames.put(uuid, visibleName);
                                                    }
                                                }
                                            }));
        }
    }

    public static IChatComponent applyPlayerName(EntityPlayer player, IChatComponent original) {
        return player == null
                ? original
                : decorate(player.getUniqueID(), player.getGameProfile().getName(), original);
    }

    public static IChatComponent applyTabName(NetworkPlayerInfo info, IChatComponent original) {
        return info == null
                ? original
                : decorate(
                        info.getGameProfile().getId(), info.getGameProfile().getName(), original);
    }

    private static IChatComponent decorate(UUID uuid, String visibleName, IChatComponent original) {
        if (!ENABLED.get()
                || uuid == null
                || original == null
                || NicknameShuffle.isEnabled()
                || ignored(Minecraft.getMinecraft(), uuid)) return original;
        String plain = original.getUnformattedText();
        IChatComponent result = original.createCopy();
        String resolved = resolvedNames.get(uuid);
        if (RESOLVE_NAMES.get()
                && resolved != null
                && visibleName != null
                && !resolved.equalsIgnoreCase(visibleName)
                && !plain.toLowerCase().contains(resolved.toLowerCase())) {
            result =
                    result.createCopy()
                            .appendSibling(
                                    new net.minecraft.util.ChatComponentText(" (")
                                            .setChatStyle(
                                                    new net.minecraft.util.ChatStyle()
                                                            .setColor(EnumChatFormatting.WHITE)))
                            .appendSibling(
                                    new net.minecraft.util.ChatComponentText(resolved)
                                            .setChatStyle(
                                                    new net.minecraft.util.ChatStyle()
                                                            .setColor(EnumChatFormatting.AQUA)))
                            .appendSibling(
                                    new net.minecraft.util.ChatComponentText(")")
                                            .setChatStyle(
                                                    new net.minecraft.util.ChatStyle()
                                                            .setColor(EnumChatFormatting.WHITE)));
        }
        if (MARK_NICK_UUID.get() && uuid.version() == 1 && !plain.contains(SUFFIX.get())) {
            result =
                    result.createCopy()
                            .appendSibling(
                                    new net.minecraft.util.ChatComponentText(" " + SUFFIX.get())
                                            .setChatStyle(
                                                    new net.minecraft.util.ChatStyle()
                                                            .setColor(EnumChatFormatting.YELLOW)
                                                            .setBold(true)));
        }
        return result;
    }

    private static boolean ignored(Minecraft client, UUID uuid) {
        if (!IGNORE_SELF.get() || client == null || uuid == null) return false;
        if (client.thePlayer != null && uuid.equals(client.thePlayer.getUniqueID())) return true;
        var connection = client.getNetHandler();
        return connection != null && uuid.equals(client.getSession().getProfile().getId());
    }

    public static int setIgnoreSelf(Minecraft ignoredClient, boolean value) {
        IGNORE_SELF.set(value);
        nextRefreshAt = 0L;
        return 1;
    }

    public static void suspend() {
        MOJANG.cancelPending();
        inFlight.clear();
        nextRefreshAt = 0L;
    }

    public static void shutdown() {
        MOJANG.close();
        inFlight.clear();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String hudTag() {
        return "Duplicate";
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        nextRefreshAt = 0L;
        ClientChat.send(client, "AntiNick " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setMarkNickUuid(Minecraft ignoredClient, boolean value) {
        MARK_NICK_UUID.set(value);
        return 1;
    }

    public static int setResolveNames(Minecraft ignoredClient, boolean value) {
        RESOLVE_NAMES.set(value);
        return 1;
    }

    public static int setSuffix(Minecraft ignoredClient, String value) {
        SUFFIX.set(value);
        return 1;
    }

    public static int setRefreshMs(Minecraft ignoredClient, int value) {
        REFRESH_MS.set(value);
        nextRefreshAt = 0L;
        return 1;
    }

    private static BooleanSetting bool(String name, boolean value) {
        return new BooleanSetting.Builder().name(name).defaultValue(value).build();
    }

    private static StringSetting string(String name, String value) {
        return new StringSetting.Builder().name(name).defaultValue(value).build();
    }

    private static IntSetting integer(String name, int value, int min, int max) {
        return new IntSetting.Builder().name(name).defaultValue(value).range(min, max).build();
    }
}
