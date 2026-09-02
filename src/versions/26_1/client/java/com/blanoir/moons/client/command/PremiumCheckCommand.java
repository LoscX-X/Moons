package com.blanoir.moons.client.command;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.service.profile.MojangProfileClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class PremiumCheckCommand {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("premiumcheck.enabled")
                    .defaultValue(true)
                    .build();
    private static final MojangProfileClient LOOKUP = new MojangProfileClient();
    private static final IntSetting INTERVAL_SECONDS = new IntSetting.Builder()
            .name("premiumcheck.intervalSeconds").defaultValue(60).range(30, 600).build();
    private static final Pattern LEGAL_JAVA_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final long REQUEST_COOLDOWN_MILLIS = 30_000L;
    private static final Map<UUID, PremiumStatus> STATUS_BY_UUID = new ConcurrentHashMap<>();
    private static final Map<String, PremiumStatus> STATUS_BY_NAME = new ConcurrentHashMap<>();
    private static boolean initialized;
    private static boolean lookupRunning;
    private static long nextLookupAtMillis;
    private static Object observedConnection;

    private PremiumCheckCommand() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        EventBus.TICK.register("PremiumCheckCommand.tick", event -> tick(event.client()));
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusText() {
        return ENABLED.get() ? "Every " + INTERVAL_SECONDS.get() + "s" : "disabled";
    }

    public static void shutdown() {
        LOOKUP.close();
        lookupRunning = false;
        STATUS_BY_UUID.clear();
        STATUS_BY_NAME.clear();
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(client, "PremiumCheck: " + statusText()
                + ". Usage: .moons premiumcheck <enable|disable|all>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        nextLookupAtMillis = 0L;
        if (!newEnabled) {
            STATUS_BY_UUID.clear();
            STATUS_BY_NAME.clear();
        }
        ClientChat.send(client, "PremiumCheck: " + statusText() + ".");
        return 1;
    }

    public static int setIntervalSeconds(Minecraft client, int seconds) {
        INTERVAL_SECONDS.set(seconds);
        nextLookupAtMillis = 0L;
        return 1;
    }

    public static Component decorate(PlayerInfo info, Component original) {
        if (!ENABLED.get() || info == null || original == null) return original;
        return decorate(status(info.getProfile().id(), info.getProfile().name()), original);
    }

    public static Component decorate(Player player, Component original) {
        if (!ENABLED.get() || player == null || original == null) return original;
        return decorate(status(player.getUUID(), player.getGameProfile().name()), original);
    }

    public static int checkAllTabPlayers(Minecraft client) {
        if (!ENABLED.get()) {
            ClientChat.send(client, "§cPremiumCheck: 已禁用，请先执行 .moons premiumcheck enable");
            return 0;
        }

        if (client.getConnection() == null) {
            ClientChat.send(client, "§cPremiumCheck: 当前没有连接到服务器");
            return 0;
        }

        long now = System.currentTimeMillis();
        if (lookupRunning) {
            ClientChat.send(client, "§ePremiumCheck: 上一次查询仍在进行，请稍候");
            return 0;
        }
        if (now < nextLookupAtMillis) {
            long seconds = Math.max(1L, (nextLookupAtMillis - now + 999L) / 1000L);
            ClientChat.send(client, "§ePremiumCheck: 请求频率受限，请在 " + seconds + " 秒后重试");
            return 0;
        }

        return beginLookup(client, true);
    }

    private static int beginLookup(Minecraft client, boolean announce) {
        if (client == null || client.getConnection() == null) return 0;
        long now = System.currentTimeMillis();
        if (lookupRunning || now < nextLookupAtMillis) return 0;

        Collection<PlayerInfo> entries = client.getConnection().getListedOnlinePlayers();
        List<String> names = new ArrayList<>();
        Map<String, UUID> serverUuids = new LinkedHashMap<>();

        for (PlayerInfo entry : entries) {
            String name = entry.getProfile().name();

            if (name != null && !name.isBlank()) {
                names.add(name);
                serverUuids.put(name.toLowerCase(Locale.ROOT), entry.getProfile().id());
            }
        }

        if (names.isEmpty()) {
            if (announce) ClientChat.send(client, "§cPremiumCheck: Tab 列表为空");
            return 0;
        }

        List<String> lookupNames = names.stream()
                .filter(PremiumCheckCommand::isLegalJavaName)
                .toList();
        int illegalNames = names.size() - lookupNames.size();

        if (announce) ClientChat.send(client, "PremiumCheck: 正在查询当前服务器 " + names.size() + " 个玩家的正版 UUID...");

        if (announce && illegalNames > 0) {
            ClientChat.send(client, "§ePremiumCheck: 已发现 " + illegalNames + " 个非法 Java 名字，将直接标记为可疑");
        }

        lookupRunning = true;
        nextLookupAtMillis = now + Math.max(REQUEST_COOLDOWN_MILLIS,
                INTERVAL_SECONDS.get() * 1000L);
        LOOKUP.lookupAsyncBatched(lookupNames).thenAccept(result -> client.execute(() -> {
                    lookupRunning = false;
                    showAllResults(client, names, serverUuids, result, announce);
                }))
                .exceptionally(ex -> {
                    client.execute(() -> {
                        lookupRunning = false;
                        if (announce) ClientChat.send(client, "§cPremiumCheck: 批量查询失败: " + rootMessage(ex));
                    });
                    return null;
                });

        return 1;
    }

    private static void showAllResults(Minecraft client, List<String> names,
                                       Map<String, UUID> serverUuids,
                                       Map<String, MojangProfileClient.MojangProfile> result,
                                       boolean announce) {
        int suspicious = 0;
        int premium = 0;

        for (String name : names) {
            MojangProfileClient.MojangProfile profile = result.get(name.toLowerCase(Locale.ROOT));
            UUID serverUuid = serverUuids.get(name.toLowerCase(Locale.ROOT));

            if (!isLegalJavaName(name)) {
                suspicious++;
                remember(name, serverUuid, PremiumStatus.INVALID);
                if (announce) ClientChat.send(client, "§c[非法名字] §f" + name + " §7不是合法 Java 玩家名");
            } else if (profile == null) {
                suspicious++;
                remember(name, serverUuid, PremiumStatus.OFFLINE);
                if (announce) ClientChat.send(client, "§e[离线] §f" + name + " §7没有正版 Java 档案");
            } else if (serverUuid == null || !uuidsMatch(serverUuid, profile.id())) {
                suspicious++;
                remember(name, serverUuid, PremiumStatus.MISMATCH);
                if (announce) ClientChat.send(client, "§c[UUID不匹配] §f" + name
                        + " §7名字存在正版档案，但当前服务器 UUID 不匹配");
            } else {
                premium++;
                remember(name, serverUuid, PremiumStatus.PREMIUM);
                if (announce) ClientChat.send(client, "§a[正版] §f" + profile.name() + " §7UUID: §f" + profile.id());
            }
        }

        if (!announce) return;
        if (suspicious == 0) {
            ClientChat.send(client, "§aPremiumCheck: 查询完成: " + premium + " 个玩家全部存在正版档案且 UUID 一致");
        } else {
            ClientChat.send(client, "§ePremiumCheck: 查询完成: 正版 " + premium + " 个，可疑 " + suspicious + " 个");
        }
    }

    private static boolean uuidsMatch(UUID serverUuid, String mojangId) {
        if (mojangId == null) {
            return false;
        }
        return serverUuid.toString().replace("-", "")
                .equalsIgnoreCase(mojangId.replace("-", ""));
    }

    private static void tick(Minecraft client) {
        Object connection = client == null ? null : client.getConnection();
        if (connection != observedConnection) {
            observedConnection = connection;
            STATUS_BY_UUID.clear();
            STATUS_BY_NAME.clear();
            nextLookupAtMillis = 0L;
        }
        if (ENABLED.get() && connection != null && !lookupRunning
                && System.currentTimeMillis() >= nextLookupAtMillis) {
            beginLookup(client, false);
        }
    }

    private static void remember(String name, UUID uuid, PremiumStatus status) {
        if (name != null) STATUS_BY_NAME.put(name.toLowerCase(Locale.ROOT), status);
        if (uuid != null) STATUS_BY_UUID.put(uuid, status);
    }

    private static PremiumStatus status(UUID uuid, String name) {
        PremiumStatus byUuid = uuid == null ? null : STATUS_BY_UUID.get(uuid);
        return byUuid != null || name == null ? byUuid
                : STATUS_BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    private static Component decorate(PremiumStatus status, Component original) {
        if (status == null) return original;
        String text = original.getString();
        for (PremiumStatus known : PremiumStatus.values()) {
            if (text.startsWith(known.label + " ") || text.equals(known.label)) return original;
        }
        return Component.empty()
                .append(Component.literal(status.label).withStyle(status.color))
                .append(Component.literal(" "))
                .append(original);
    }

    private enum PremiumStatus {
        PREMIUM("[正版]", ChatFormatting.GREEN),
        OFFLINE("[离线]", ChatFormatting.YELLOW),
        MISMATCH("[UUID异常]", ChatFormatting.RED),
        INVALID("[非法名字]", ChatFormatting.DARK_RED);

        private final String label;
        private final ChatFormatting color;
        PremiumStatus(String label, ChatFormatting color) {
            this.label = label;
            this.color = color;
        }
    }

    private static boolean isLegalJavaName(String name) {
        return name != null && LEGAL_JAVA_NAME.matcher(name).matches();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
