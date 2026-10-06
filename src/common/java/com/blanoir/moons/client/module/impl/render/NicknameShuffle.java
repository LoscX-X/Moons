package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.event.EventBus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Session-local display identities. Real profiles and signed messages remain untouched. */
public final class NicknameShuffle {
    private record Snapshot(
            Map<UUID, NetworkPlayerInfo> sources,
            Map<UUID, String> aliases,
            Map<String, String> names) {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of());
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static final ThreadLocal<Boolean> READING_SKIN = ThreadLocal.withInitial(() -> false);
    private static NetHandlerPlayClient connection;
    private static List<NetworkPlayerInfo> roster = List.of();

    private NicknameShuffle() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "NicknameShuffle.context",
                event -> {
                    if (event.current().connection() != connection) reset();
                });
        EventBus.TICK_END.register(
                "NicknameShuffle.tick",
                event -> {
                    if (connection == null) return;
                    if (event.client().getNetHandler() != connection) {
                        reset();
                        return;
                    }
                    List<NetworkPlayerInfo> current = players(connection);
                    if (!current.equals(roster)) shuffle(current, RandomGenerator.getDefault());
                });
    }

    public static void command(Minecraft client) {
        var current = client == null ? null : client.getNetHandler();
        if (current == null) {
            ClientChat.send(client, "请先进入世界，再使用 .nick all。");
            return;
        }
        List<NetworkPlayerInfo> players = players(current);
        if (players.isEmpty()) {
            ClientChat.send(client, "暂时没有可混淆的玩家。");
            return;
        }
        connection = current;
        shuffle(players, RandomGenerator.getDefault());
        ClientChat.send(
                client,
                "已在本地混淆 " + players.size() + " 名玩家的名字和皮肤。再次 .nick all 重新混淆，.nick all reset 恢复。");
    }

    private static List<NetworkPlayerInfo> players(NetHandlerPlayClient current) {
        return current.getPlayerInfoMap().stream()
                .sorted(java.util.Comparator.comparing(info -> info.getGameProfile().getId()))
                .toList();
    }

    static void shuffle(List<NetworkPlayerInfo> players, RandomGenerator random) {
        // Skin donors form a random cycle, independent of the generated display aliases.
        var sources = new ArrayList<>(players);
        for (int index = sources.size() - 1; index > 0; index--) {
            java.util.Collections.swap(sources, index, random.nextInt(index));
        }
        Map<UUID, NetworkPlayerInfo> byId = new LinkedHashMap<>();
        Map<String, String> byName = new LinkedHashMap<>();
        Map<UUID, String> aliases = new LinkedHashMap<>();
        var reserved = new java.util.HashSet<String>();
        for (var player : players)
            reserved.add(player.getGameProfile().getName().toLowerCase(Locale.ROOT));
        for (int index = 0; index < players.size(); index++) {
            var profile = players.get(index).getGameProfile();
            var source = sources.get(index);
            byId.put(profile.getId(), source);
            String alias;
            do {
                alias = "Player_" + String.format(Locale.ROOT, "%08x", random.nextInt());
            } while (!reserved.add(alias.toLowerCase(Locale.ROOT)));
            aliases.put(profile.getId(), alias);
            byName.put(profile.getName().toLowerCase(Locale.ROOT), alias);
        }
        roster = List.copyOf(players);
        snapshot = new Snapshot(Map.copyOf(byId), Map.copyOf(aliases), Map.copyOf(byName));
    }

    public static boolean isEnabled() {
        return !snapshot.sources().isEmpty();
    }

    public static String name(UUID player) {
        return snapshot.aliases().get(player);
    }

    public static ResourceLocation skin(NetworkPlayerInfo player, ResourceLocation original) {
        if (READING_SKIN.get()) return original;
        NetworkPlayerInfo source = snapshot.sources().get(player.getGameProfile().getId());
        if (source == null || source == player) return original;
        // Read the donor's current skin so asynchronous skin downloads are reflected too.
        // Its transformed getSkin() must not follow another edge in the shuffle cycle.
        READING_SKIN.set(true);
        try {
            return source.getLocationSkin();
        } finally {
            READING_SKIN.remove();
        }
    }

    public static IChatComponent chat(IChatComponent original) {
        return replaceChat(original, snapshot.names());
    }

    private static IChatComponent replaceChat(IChatComponent original, Map<String, String> names) {
        if (original == null || names.isEmpty()) return original;
        IChatComponent result;
        if (original instanceof ChatComponentText plain) {
            result =
                    new ChatComponentText(
                            replaceNames(plain.getChatComponentText_TextValue(), names));
        } else if (original instanceof ChatComponentTranslation translated) {
            Object[] args = translated.getFormatArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof IChatComponent part) args[i] = replaceChat(part, names);
                else if (args[i] instanceof String text) args[i] = replaceNames(text, names);
            }
            result = new ChatComponentTranslation(translated.getKey(), args);
        } else {
            result = original.createCopy();
            result.getSiblings().clear();
        }
        result.setChatStyle(original.getChatStyle().createShallowCopy());
        for (IChatComponent sibling : original.getSiblings())
            result.appendSibling(replaceChat(sibling, names));
        return result;
    }

    private static String replaceNames(String text, Map<String, String> names) {
        var matcher = java.util.regex.Pattern.compile("[A-Za-z0-9_]+").matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement =
                    names.getOrDefault(matcher.group().toLowerCase(Locale.ROOT), matcher.group());
            matcher.appendReplacement(
                    result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static void commandReset(Minecraft client) {
        reset();
        ClientChat.send(client, "已关闭全员混淆，恢复原始皮肤；自己的昵称设置保留。");
    }

    public static void reset() {
        snapshot = Snapshot.EMPTY;
        roster = List.of();
        connection = null;
    }
}
