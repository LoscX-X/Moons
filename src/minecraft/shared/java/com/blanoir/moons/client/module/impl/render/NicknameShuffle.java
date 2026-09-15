package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.event.EventBus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.PlayerSkin;

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
            Map<UUID, PlayerInfo> sources, Map<UUID, String> aliases, Map<String, String> names) {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of());
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static final ThreadLocal<Boolean> READING_SKIN = ThreadLocal.withInitial(() -> false);
    private static ClientPacketListener connection;
    private static List<PlayerInfo> roster = List.of();

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
                    if (event.client().getConnection() != connection) {
                        reset();
                        return;
                    }
                    List<PlayerInfo> current = players(connection);
                    if (!current.equals(roster)) shuffle(current, RandomGenerator.getDefault());
                });
    }

    public static void command(Minecraft client) {
        var current = client == null ? null : client.getConnection();
        if (current == null) {
            ClientChat.send(client, "请先进入世界，再使用 .nick all。");
            return;
        }
        List<PlayerInfo> players = players(current);
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

    private static List<PlayerInfo> players(ClientPacketListener current) {
        return current.getOnlinePlayers().stream()
                .sorted(java.util.Comparator.comparing(info -> info.getProfile().id()))
                .toList();
    }

    static void shuffle(List<PlayerInfo> players, RandomGenerator random) {
        // Skin donors form a random cycle, independent of the generated display aliases.
        var sources = new ArrayList<>(players);
        for (int index = sources.size() - 1; index > 0; index--) {
            java.util.Collections.swap(sources, index, random.nextInt(index));
        }
        Map<UUID, PlayerInfo> byId = new LinkedHashMap<>();
        Map<String, String> byName = new LinkedHashMap<>();
        Map<UUID, String> aliases = new LinkedHashMap<>();
        var reserved = new java.util.HashSet<String>();
        for (var player : players)
            reserved.add(player.getProfile().name().toLowerCase(Locale.ROOT));
        for (int index = 0; index < players.size(); index++) {
            var profile = players.get(index).getProfile();
            var source = sources.get(index);
            byId.put(profile.id(), source);
            String alias;
            do {
                alias = "Player_" + String.format(Locale.ROOT, "%08x", random.nextInt());
            } while (!reserved.add(alias.toLowerCase(Locale.ROOT)));
            aliases.put(profile.id(), alias);
            byName.put(profile.name().toLowerCase(Locale.ROOT), alias);
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

    public static PlayerSkin skin(PlayerInfo player, PlayerSkin original) {
        if (READING_SKIN.get()) return original;
        PlayerInfo source = snapshot.sources().get(player.getProfile().id());
        if (source == null || source == player) return original;
        // Read the donor's current skin so asynchronous skin downloads are reflected too.
        // Its transformed getSkin() must not follow another edge in the shuffle cycle.
        READING_SKIN.set(true);
        try {
            return source.getSkin();
        } finally {
            READING_SKIN.remove();
        }
    }

    public static Component chat(Component original) {
        return replaceChat(original, snapshot.names());
    }

    private static Component replaceChat(Component original, Map<String, String> names) {
        if (original == null || names.isEmpty()) return original;
        var contents = original.getContents();
        if (contents instanceof PlainTextContents plain) {
            contents = Component.literal(replaceNames(plain.text(), names)).getContents();
        } else if (contents instanceof TranslatableContents translated) {
            Object[] arguments = translated.getArgs().clone();
            for (int index = 0; index < arguments.length; index++) {
                if (arguments[index] instanceof Component component) {
                    arguments[index] = replaceChat(component, names);
                } else if (arguments[index] instanceof String text) {
                    arguments[index] = replaceNames(text, names);
                }
            }
            contents =
                    new TranslatableContents(
                            translated.getKey(), translated.getFallback(), arguments);
        }
        MutableComponent result = MutableComponent.create(contents).setStyle(original.getStyle());
        for (Component sibling : original.getSiblings()) result.append(replaceChat(sibling, names));
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
