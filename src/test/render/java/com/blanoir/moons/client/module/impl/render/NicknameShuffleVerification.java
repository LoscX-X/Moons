package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.features.FeatureHooks;
import com.blanoir.moons.runtime.RuntimeEvents;
import com.mojang.authlib.GameProfile;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.PlayerSkin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Exercises the feature hooks with real components and skin records, without a graphics context. */
public final class NicknameShuffleVerification {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var players = new ArrayList<PlayerInfo>();
        var base = DefaultPlayerSkin.getDefaultSkin();
        for (int index = 0; index < 32; index++) {
            var player = (FixtureInfo) unsafe.allocateInstance(FixtureInfo.class);
            player.profile =
                    new GameProfile(
                            new UUID(0, index + 1),
                            index == 0 ? "Alice" : index == 1 ? "Bob" : "Player" + index);
            player.nativeSkin =
                    new PlayerSkin(base.body(), base.cape(), base.elytra(), base.model(), true);
            players.add(player);
        }
        try {
            for (int size = 1; size <= players.size(); size++) {
                for (int seed = 0; seed < 12; seed++) {
                    List<PlayerInfo> current = players.subList(0, size);
                    NicknameShuffle.shuffle(current, new Random(seed));
                    var assigned = new HashSet<String>();
                    for (PlayerInfo player : current) {
                        String name = NicknameShuffle.name(player.getProfile().id());
                        require(
                                !name.equals(player.getProfile().name()),
                                "Every player changes identity");
                        require(assigned.add(name), "Identities remain unique");
                        require(
                                current.stream()
                                        .noneMatch(info -> info.getProfile().name().equals(name)),
                                "Aliases reveal none of the original roster names");
                        FixtureInfo donor = donor(current, player.getSkin());
                        require(
                                size == 1 || donor != player,
                                "Skin donors are independently shuffled");
                    }
                }
            }

            NicknameShuffle.shuffle(players.subList(0, 3), new Random(7));
            PlayerInfo alice = players.get(0);
            String aliceName = NicknameShuffle.name(alice.getProfile().id());
            String bobName = NicknameShuffle.name(players.get(1).getProfile().id());
            var original =
                    Component.literal("[VIP] ")
                            .withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("Alice").withStyle(ChatFormatting.AQUA))
                            .append("! Alice_2");
            var nameColor = original.getSiblings().getFirst().getStyle().getColor();
            var tab = new RuntimeEvents.MethodHook("render.tab-name", null, alice, original);
            FeatureHooks.apply(tab);
            require(
                    ((Component) tab.value())
                            .getString()
                            .equals("[VIP] " + aliceName + "! Alice_2"),
                    "Tab replacement respects name boundaries and team decorations");
            require(
                    ((Component) tab.value())
                            .toFlatList().stream()
                                    .anyMatch(
                                            part ->
                                                    part.getString().equals(aliceName)
                                                            && part.getStyle()
                                                                    .getColor()
                                                                    .equals(nameColor)),
                    "Name color is preserved");
            require(
                    original.getString().equals("[VIP] Alice! Alice_2"),
                    "Original component is untouched");

            require(
                    Nickname.replaceTabName(alice, (Component) tab.value())
                            .getString()
                            .equals(((Component) tab.value()).getString()),
                    "Repeated name hooks preserve team decorations");
            require(
                    Nickname.replaceTabName(alice, Component.literal("Custom server nickname"))
                            .getString()
                            .equals(aliceName),
                    "Custom tab names also become anonymous");

            var splitName = Component.literal("Al").append(Component.literal("ice"));
            require(
                    Nickname.replaceTabName(alice, splitName).getString().equals(aliceName),
                    "Names split across styled components are replaced");

            var chat = Component.literal("Alice").append(" / ").append(Component.literal("Bob"));
            var message = new RuntimeEvents.MethodHook("render.chat-player-name", null, null, chat);
            FeatureHooks.apply(message);
            require(
                    ((Component) message.value()).getString().equals(aliceName + " / " + bobName),
                    "Chat applies the permutation once, without cascading names");
            var translated =
                    Component.translatableWithFallback(
                            "moons.nickname.test", "%s / %s", Component.literal("Alice"), "Bob");
            require(
                    NicknameShuffle.chat(translated)
                            .getString()
                            .equals(aliceName + " / " + bobName),
                    "Translated sender components and arguments are replaced");
            require(
                    NicknameShuffle.chat(Component.literal("Alice says Bob"))
                            .getString()
                            .equals(aliceName + " says " + bobName),
                    "Unstructured chat mentions also hide original names");

            FixtureInfo donor = donor(players, alice.getSkin());
            donor.nativeSkin = new PlayerSkin(base.body(), null, null, base.model(), false);
            require(alice.getSkin() == donor.nativeSkin, "Late skin downloads are reflected");
            donor.fail = true;
            try {
                alice.getSkin();
                throw new AssertionError("Expected donor failure");
            } catch (IllegalStateException expected) {
                // A failed donor lookup must still release the recursion guard.
            } finally {
                donor.fail = false;
            }
            require(alice.getSkin() == donor.nativeSkin, "Skin guard is released after failure");

            NicknameShuffle.shuffle(players.subList(0, 2), new Random(3));
            require(
                    NicknameShuffle.name(players.get(2).getProfile().id()) == null,
                    "Departed players are removed");
            Nickname.commandSet(null, "Solo");
            require(NicknameShuffle.isEnabled(), "Setting own nickname keeps global obfuscation");
            Nickname.commandReset(null);
            require(
                    NicknameShuffle.isEnabled() && Nickname.value().isEmpty(),
                    "Own reset changes only own nickname");
            Nickname.commandSet(null, "Solo");
            NicknameShuffle.commandReset(null);
            require(
                    !NicknameShuffle.isEnabled() && Nickname.value().equals("Solo"),
                    "Global reset preserves own nickname");
            Nickname.commandReset(null);
            require(
                    alice.getSkin() == ((FixtureInfo) alice).nativeSkin,
                    "Reset restores original skin");
            require(
                    Nickname.replaceTabName(alice, original) == original,
                    "Reset restores original name");
            NicknameShuffle.shuffle(players.subList(0, 3), new Random(5));
            Nickname.commandSet(null, "Solo");
            require(
                    NicknameShuffle.isEnabled() && Nickname.value().equals("Solo"),
                    "Own nickname remains independent of all-player mode");
            System.out.println(
                    "NICKNAME_SHUFFLE_VERIFIED names=random+unique+styled skins=independent+live+guarded reset=isolated");
            NicknameShuffle.reset();
            verifyAntiNick(unsafe, players);
        } finally {
            NicknameShuffle.reset();
            Nickname.commandReset(null);
        }
    }

    private static void verifyAntiNick(sun.misc.Unsafe unsafe, List<PlayerInfo> players)
            throws Exception {
        var client =
                (net.minecraft.client.Minecraft)
                        unsafe.allocateInstance(net.minecraft.client.Minecraft.class);
        var self =
                (net.minecraft.client.player.LocalPlayer)
                        unsafe.allocateInstance(net.minecraft.client.player.LocalPlayer.class);
        UUID selfId = UUID.fromString("00000000-0000-1000-8000-000000000001");
        self.setUUID(selfId);
        client.player = self;
        Field singleton = net.minecraft.client.Minecraft.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        var info = (FixtureInfo) unsafe.allocateInstance(FixtureInfo.class);
        info.profile = new GameProfile(selfId, "Self");
        var original = Component.literal("Self");
        try {
            singleton.set(null, client);
            com.blanoir.moons.client.module.impl.misc.AntiNick.setEnabled(null, true);
            com.blanoir.moons.client.module.impl.misc.AntiNick.setIgnoreSelf(null, true);
            require(
                    com.blanoir.moons.client.module.impl.misc.AntiNick.applyTabName(info, original)
                            == original,
                    "Ignore self suppresses own UUID marker");
            com.blanoir.moons.client.module.impl.misc.AntiNick.setIgnoreSelf(null, false);
            require(
                    com.blanoir.moons.client.module.impl.misc.AntiNick.applyTabName(info, original)
                            .getString()
                            .contains("[Nick]"),
                    "Turning ignore self off resumes own marker");
            NicknameShuffle.shuffle(players, new Random(17));
            require(
                    com.blanoir.moons.client.module.impl.misc.AntiNick.applyTabName(info, original)
                            == original,
                    "Global obfuscation suppresses AntiNick annotations");
        } finally {
            com.blanoir.moons.client.module.impl.misc.AntiNick.setEnabled(null, false);
            com.blanoir.moons.client.module.impl.misc.AntiNick.setIgnoreSelf(null, true);
            NicknameShuffle.reset();
            singleton.set(null, previous);
        }
    }

    private static FixtureInfo donor(List<PlayerInfo> players, PlayerSkin skin) {
        return (FixtureInfo)
                players.stream()
                        .filter(player -> ((FixtureInfo) player).nativeSkin == skin)
                        .findFirst()
                        .orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FixtureInfo extends PlayerInfo {
        GameProfile profile;
        PlayerSkin nativeSkin;
        boolean fail;

        private FixtureInfo() {
            super(new GameProfile(new UUID(0, 0), "fixture"), false);
        }

        @Override
        public GameProfile getProfile() {
            return profile;
        }

        @Override
        public PlayerSkin getSkin() {
            if (fail) throw new IllegalStateException("Fixture skin download failed");
            var hook = new RuntimeEvents.MethodHook("render.player-skin", this, null, nativeSkin);
            FeatureHooks.apply(hook);
            return (PlayerSkin) hook.value();
        }
    }
}
