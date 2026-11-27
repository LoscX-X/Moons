package com.blanoir.tempos.client.module.impl.misc;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;

import com.blanoir.tempos.api.ScopedResources;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.StringSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.profile.MojangProfileService;
import com.blanoir.tempos.client.concurrent.ThreadBoundExecutor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Combined nickname marker and original-name resolver. */
@ModuleInfo(
        category = ModuleCategory.MISC,
        description = "Marks nicknames and can show a resolved original name.")
public final class AntiNick extends ClientFeature {
    public static final AntiNick INSTANCE = new AntiNick();

    private final BooleanSetting enabled = bool("antinick.enabled", false);
    private final BooleanSetting markNickUuid = bool("antinick.markNickUuid", true);
    private final BooleanSetting ignoreSelf = bool("antinick.ignoreSelf", true);
    private final BooleanSetting resolveNames = bool("antinick.resolveNames", true);
    private final StringSetting suffix = string("antinick.suffix", "[Nick]");
    private final IntSetting refreshMs = integer("antinick.refreshMs", 5000, 500, 15000);
    private final MojangProfileService mojang = new MojangProfileService();
    private final Map<UUID, String> resolvedNames = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retryAt = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private long nextRefreshAt;

    private BooleanSupplier shuffledNames = () -> false;

    public void setShuffledNamesCheck(BooleanSupplier check) {
        shuffledNames = check;
    }

    private AntiNick() {
        super("antinick", "AntiNick");
    }

    @Override
    public void registerEvents() {
        ScopedResources.own(this.mojang);
        EventBus.TICK_END.register("AntiNick.tickEnd", event -> tick(event.client()));
    }

    private void tick(Minecraft client) {
        var connection = client == null ? null : client.getConnection();
        if (!this.enabled.get()
                || !this.resolveNames.get()
                || shuffledNames.getAsBoolean()
                || client == null
                || connection == null) return;
        long now = System.currentTimeMillis();
        if (now < this.nextRefreshAt) return;
        this.nextRefreshAt = now + this.refreshMs.get();
        var domain =
                new ThreadBoundExecutor("AntiNick result", client::isSameThread, client::execute);
        var lifetime = this.mojang.lifetime();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            UUID uuid = info.getProfile().id();
            String visibleName = info.getProfile().name();
            if (uuid == null
                    || ignored(client, uuid)
                    || visibleName == null
                    || this.resolvedNames.containsKey(uuid)
                    || this.inFlight.contains(uuid)
                    || this.retryAt.getOrDefault(uuid, 0L) > now) continue;
            this.inFlight.add(uuid);
            this.mojang
                    .lookupByUuidAsync(uuid)
                    .whenComplete(
                            (profile, failure) ->
                                    domain.execute(
                                            lifetime,
                                            () -> {
                                                this.inFlight.remove(uuid);
                                                if (!this.enabled.get()
                                                        || ignored(client, uuid)
                                                        || shuffledNames.getAsBoolean()
                                                        || client.getConnection() != connection)
                                                    return;
                                                if (failure != null) {
                                                    this.retryAt.put(
                                                            uuid,
                                                            System.currentTimeMillis() + 30_000L);
                                                } else if (profile == null) {
                                                    this.retryAt.put(
                                                            uuid,
                                                            System.currentTimeMillis() + 60_000L);
                                                } else {
                                                    this.retryAt.remove(uuid);
                                                    if (!profile.name()
                                                            .equalsIgnoreCase(visibleName)) {
                                                        this.resolvedNames.put(
                                                                uuid, profile.name());
                                                        ClientChat.send(
                                                                client,
                                                                "§lAntiNick resolved §r"
                                                                        + visibleName
                                                                        + " §7-> §b"
                                                                        + profile.name());
                                                    } else {
                                                        this.resolvedNames.put(uuid, visibleName);
                                                    }
                                                }
                                            }));
        }
    }

    public Component applyPlayerName(Player player, Component original) {
        return player == null
                ? original
                : decorate(player.getUUID(), player.getGameProfile().name(), original);
    }

    public Component applyTabName(PlayerInfo info, Component original) {
        return info == null
                ? original
                : decorate(info.getProfile().id(), info.getProfile().name(), original);
    }

    private Component decorate(UUID uuid, String visibleName, Component original) {
        if (!this.enabled.get()
                || uuid == null
                || original == null
                || shuffledNames.getAsBoolean()
                || ignored(Minecraft.getInstance(), uuid)) return original;
        String plain = original.getString();
        Component result = original.copy();
        String resolved = this.resolvedNames.get(uuid);
        if (this.resolveNames.get()
                && resolved != null
                && visibleName != null
                && !resolved.equalsIgnoreCase(visibleName)
                && !plain.toLowerCase().contains(resolved.toLowerCase())) {
            result =
                    result.copy()
                            .append(Component.literal(" (").withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(resolved).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(")").withStyle(ChatFormatting.WHITE));
        }
        if (this.markNickUuid.get() && uuid.version() == 1 && !plain.contains(this.suffix.get())) {
            result =
                    result.copy()
                            .append(
                                    Component.literal(" " + this.suffix.get())
                                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        }
        return result;
    }

    private boolean ignored(Minecraft client, UUID uuid) {
        if (!this.ignoreSelf.get() || client == null || uuid == null) return false;
        if (client.player != null && uuid.equals(client.player.getUUID())) return true;
        var connection = client.getConnection();
        return connection != null && uuid.equals(connection.getLocalGameProfile().id());
    }

    public int setIgnoreSelf(Minecraft ignoredClient, boolean value) {
        this.ignoreSelf.set(value);
        this.nextRefreshAt = 0L;
        return 1;
    }

    public void shutdown() {
        this.mojang.close();
        this.inFlight.clear();
    }

    public void suspend() {
        this.mojang.cancelPending();
        this.inFlight.clear();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public static String hudTag() {
        return "Duplicate";
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        this.nextRefreshAt = 0L;
        ClientChat.send(client, "AntiNick " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setMarkNickUuid(Minecraft ignoredClient, boolean value) {
        this.markNickUuid.set(value);
        return 1;
    }

    public int setResolveNames(Minecraft ignoredClient, boolean value) {
        this.resolveNames.set(value);
        return 1;
    }

    public int setSuffix(Minecraft ignoredClient, String value) {
        this.suffix.set(value);
        return 1;
    }

    public int setRefreshMs(Minecraft ignoredClient, int value) {
        this.refreshMs.set(value);
        this.nextRefreshAt = 0L;
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

    @Override
    protected String statusTag() {
        return AntiNick.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            ignoreSelf.describeStored("ignore_self", "Ignore self", this::setIgnoreSelf),
            markNickUuid.describeStored("mark_nick_uuid", "Mark nick UUID", this::setMarkNickUuid),
            resolveNames.describeStored(
                    "resolve_names", "Resolve original names", this::setResolveNames),
            suffix.describeText("suffix", "Nick suffix", this::setSuffix),
            refreshMs.describeStored("refresh", "Refresh ms", 100, this::setRefreshMs)
        };
    }
}
