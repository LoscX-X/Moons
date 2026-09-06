package com.blanoir.moons.client.module.impl.misc.antibot;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.utils.player.PlayerListUtils;
import com.blanoir.moons.client.management.targeting.Targeting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/** LiquidBounce-compatible bot filtering shared by every combat target selector. */
public final class AntiBot {
    private static final AntiBotState STATE = new AntiBotState();

    private static final BooleanSetting ENABLED = bool("antibot.enabled", false);
    private static final ModeSetting<AntiBotMode> MODE = new ModeSetting.Builder<AntiBotMode>()
            .name("antibot.mode").defaultValue(AntiBotMode.CUSTOM)
            .option(AntiBotMode.CUSTOM, "custom")
            .option(AntiBotMode.MATRIX, "matrix")
            .option(AntiBotMode.INTAVE_HEAVY, "intave_heavy")
            .option(AntiBotMode.HORIZON, "horizon").build();
    private static final BooleanSetting LITERAL_NPC = bool("antibot.literalNpc", false);
    private static final BooleanSetting NOT_IN_TAB = bool("antibot.notInTabList", false);
    private static final BooleanSetting INVALID_GROUND = bool("antibot.custom.invalidGround", true);
    private static final IntSetting INVALID_GROUND_VL = integer("antibot.custom.invalidGroundVl", 10, 1, 50);
    private static final BooleanSetting ALWAYS_IN_RADIUS = bool("antibot.custom.alwaysInRadius", false);
    private static final DoubleSetting RADIUS = number("antibot.custom.radius", 20.0D, 5.0D, 30.0D);
    private static final BooleanSetting AGE_CHECK = bool("antibot.custom.age", false);
    private static final IntSetting MINIMUM_AGE = integer("antibot.custom.minimumAge", 20, 0, 120);
    private static final BooleanSetting NAME_CHECK = bool("antibot.custom.name", true);
    private static final IntSetting NAME_MIN = integer("antibot.custom.nameMin", 3, 1, 32);
    private static final IntSetting NAME_MAX = integer("antibot.custom.nameMax", 16, 1, 32);
    private static final BooleanSetting DUPLICATE = bool("antibot.custom.duplicate", false);
    private static final BooleanSetting NO_GAME_MODE = bool("antibot.custom.noGameMode", true);
    private static final BooleanSetting ILLEGAL_PITCH = bool("antibot.custom.illegalPitch", true);
    private static final BooleanSetting FAKE_ENTITY_ID = bool("antibot.custom.fakeEntityId", true);
    private static final BooleanSetting NEED_HIT = bool("antibot.custom.needHit", false);
    private static final BooleanSetting ILLEGAL_HEALTH = bool("antibot.custom.illegalHealth", false);
    private static final BooleanSetting NEED_SWING = bool("antibot.custom.needSwing", false);
    private static final BooleanSetting NEED_CRIT = bool("antibot.custom.needCrit", false);
    private static final BooleanSetting NEED_ATTRIBUTES = bool("antibot.custom.needAttributes", false);
    private static final BooleanSetting ILLEGAL_SCALE = bool("antibot.custom.illegalScale", false);

    private static boolean initialized;

    private AntiBot() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        Targeting.setIgnoreCheck(AntiBot::isBot);
        EventBus.TICK.register("AntiBot.tick", event -> STATE.tick(event.client()));
        EventBus.PACKET_RECEIVE_APPLY.register("AntiBot.packetReceiveApply", event ->
                STATE.handlePacket(Minecraft.getInstance(), event.packet()));
    }

    public static boolean isBot(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (!ENABLED.get() || currentPlayer == null || entity == currentPlayer
                || !(entity instanceof Player player)) return false;
        if (LITERAL_NPC.get() && !PlayerListUtils.isOnline(client, player.getUUID())) return true;
        if (NOT_IN_TAB.get() && !PlayerListUtils.isListed(client, player.getUUID())) return true;
        return STATE.isModeBot(mode(), client, player);
    }

    public static void markHit(Entity entity) {
        if (ENABLED.get()) STATE.markHit(entity);
    }

    public static boolean isEnabled() { return ENABLED.get(); }
    public static String hudTag() { return MODE.serialized(); }
    static AntiBotMode mode() { return MODE.get(); }
    public static List<String> modeOptions() { return MODE.optionIds(); }
    public static boolean customMode() { return MODE.get() == AntiBotMode.CUSTOM; }
    public static boolean invalidGroundEnabled() { return INVALID_GROUND.get(); }
    public static boolean radiusEnabled() { return ALWAYS_IN_RADIUS.get(); }
    public static boolean ageEnabled() { return AGE_CHECK.get(); }
    public static boolean nameEnabled() { return NAME_CHECK.get(); }
    static boolean invalidGround() { return INVALID_GROUND.get(); }
    static int invalidGroundVl() { return INVALID_GROUND_VL.get(); }
    static boolean alwaysInRadius() { return ALWAYS_IN_RADIUS.get(); }
    static double radius() { return RADIUS.get(); }
    static boolean ageCheck() { return AGE_CHECK.get(); }
    static int minimumAge() { return MINIMUM_AGE.get(); }
    static boolean nameCheck() { return NAME_CHECK.get(); }
    static int nameMinLength() { return Math.min(NAME_MIN.get(), NAME_MAX.get()); }
    static int nameMaxLength() { return Math.max(NAME_MIN.get(), NAME_MAX.get()); }
    static boolean duplicateCheck() { return DUPLICATE.get(); }
    static boolean noGameMode() { return NO_GAME_MODE.get(); }
    static boolean illegalPitch() { return ILLEGAL_PITCH.get(); }
    static boolean fakeEntityId() { return FAKE_ENTITY_ID.get(); }
    static boolean needHit() { return NEED_HIT.get(); }
    static boolean illegalHealth() { return ILLEGAL_HEALTH.get(); }
    static boolean needSwing() { return NEED_SWING.get(); }
    static boolean needCrit() { return NEED_CRIT.get(); }
    static boolean needAttributes() { return NEED_ATTRIBUTES.get(); }
    static boolean illegalScale() { return ILLEGAL_SCALE.get(); }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) STATE.reset();
        ClientChat.send(client, "AntiBot " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setMode(Minecraft ignoredClient, String value) {
        MODE.deserialize(value);
        STATE.reset();
        return 1;
    }

    public static int setLiteralNpc(Minecraft ignoredClient, boolean value) { LITERAL_NPC.set(value); return 1; }
    public static int setNotInTab(Minecraft ignoredClient, boolean value) { NOT_IN_TAB.set(value); return 1; }
    public static int setInvalidGround(Minecraft ignoredClient, boolean value) { INVALID_GROUND.set(value); return 1; }
    public static int setInvalidGroundVl(Minecraft ignoredClient, int value) { INVALID_GROUND_VL.set(value); return 1; }
    public static int setAlwaysInRadius(Minecraft ignoredClient, boolean value) { ALWAYS_IN_RADIUS.set(value); return 1; }
    public static int setRadius(Minecraft ignoredClient, double value) { RADIUS.set(value); return 1; }
    public static int setAgeCheck(Minecraft ignoredClient, boolean value) { AGE_CHECK.set(value); return 1; }
    public static int setMinimumAge(Minecraft ignoredClient, int value) { MINIMUM_AGE.set(value); return 1; }
    public static int setNameCheck(Minecraft ignoredClient, boolean value) { NAME_CHECK.set(value); return 1; }
    public static int setNameMin(Minecraft ignoredClient, int value) { NAME_MIN.set(value); return 1; }
    public static int setNameMax(Minecraft ignoredClient, int value) { NAME_MAX.set(value); return 1; }
    public static int setDuplicate(Minecraft ignoredClient, boolean value) { DUPLICATE.set(value); return 1; }
    public static int setNoGameMode(Minecraft ignoredClient, boolean value) { NO_GAME_MODE.set(value); return 1; }
    public static int setIllegalPitch(Minecraft ignoredClient, boolean value) { ILLEGAL_PITCH.set(value); return 1; }
    public static int setFakeEntityId(Minecraft ignoredClient, boolean value) { FAKE_ENTITY_ID.set(value); return 1; }
    public static int setNeedHit(Minecraft ignoredClient, boolean value) { NEED_HIT.set(value); return 1; }
    public static int setIllegalHealth(Minecraft ignoredClient, boolean value) { ILLEGAL_HEALTH.set(value); return 1; }
    public static int setNeedSwing(Minecraft ignoredClient, boolean value) { NEED_SWING.set(value); return 1; }
    public static int setNeedCrit(Minecraft ignoredClient, boolean value) { NEED_CRIT.set(value); return 1; }
    public static int setNeedAttributes(Minecraft ignoredClient, boolean value) { NEED_ATTRIBUTES.set(value); return 1; }
    public static int setIllegalScale(Minecraft ignoredClient, boolean value) { ILLEGAL_SCALE.set(value); return 1; }

    private static BooleanSetting bool(String key, boolean fallback) {
        return new BooleanSetting.Builder().name(key).defaultValue(fallback).build();
    }

    private static IntSetting integer(String key, int fallback, int min, int max) {
        return new IntSetting.Builder().name(key).defaultValue(fallback).range(min, max).build();
    }

    private static DoubleSetting number(String key, double fallback, double min, double max) {
        return new DoubleSetting.Builder().name(key).defaultValue(fallback).range(min, max).build();
    }

}
