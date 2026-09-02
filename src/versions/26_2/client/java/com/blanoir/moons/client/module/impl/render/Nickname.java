package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.utils.text.DynamicMiniMessage;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/** Local-only replacement for the signed-in player's visible name. */
public final class Nickname {
    private static final int MAX_CODE_POINTS = 64;
    private static final int MAX_MARKUP_CODE_POINTS = 512;
    private static final StringSetting VALUE = new StringSetting.Builder()
            .name("nickname.value")
            .defaultValue("")
            .build();
    private static String cachedMarkup;
    private static long renderFrame;
    private static long renderTimeMillis;
    private static long cachedRenderFrame = Long.MIN_VALUE;
    private static Component cachedStyledValue = Component.empty();
    private static final MutableComponent LIVE_CHAT_STYLED_VALUE = Component.empty();
    private static boolean chatStyledValueUsed;
    private static long nextChatRefreshMillis;

    private Nickname() {
    }

    public static void init() {
        EventBus.FRAME.register("Nickname.frame", event -> {
            renderFrame++;
            renderTimeMillis = System.nanoTime() / 1_000_000L;
            if (isEnabled()) {
                styledValue();
                if (chatStyledValueUsed && isAnimatedMarkup(VALUE.get())
                        && renderTimeMillis >= nextChatRefreshMillis
                        && !MinecraftClientAccess.isChatOpen(event.client())) {
                    nextChatRefreshMillis = renderTimeMillis + 50L;
                    MinecraftClientAccess.refreshChat(event.client());
                }
            }
        });
    }

    public static boolean isEnabled() {
        return !VALUE.get().isBlank();
    }

    public static String value() {
        return VALUE.get();
    }

    public static synchronized Component styledValue() {
        String markup = VALUE.get();
        long frame = renderFrame;
        long timeMillis = frame == 0L
                ? System.nanoTime() / 1_000_000L : renderTimeMillis;
        if (!markup.equals(cachedMarkup) || frame != cachedRenderFrame) {
            cachedMarkup = markup;
            cachedRenderFrame = frame;
            cachedStyledValue = DynamicMiniMessage.parse(markup, timeMillis);
            Component refreshedChatValue = withoutImageGlyphs(cachedStyledValue);
            LIVE_CHAT_STYLED_VALUE.getSiblings().clear();
            LIVE_CHAT_STYLED_VALUE.append(refreshedChatValue);
        }
        return cachedStyledValue;
    }

    public static synchronized Component chatStyledValue() {
        // styledValue refreshes both variants once for the current render frame.
        styledValue();
        chatStyledValueUsed = true;
        return LIVE_CHAT_STYLED_VALUE;
    }

    public static boolean appliesTo(Player player) {
        Minecraft client = Minecraft.getInstance();
        return isEnabled() && client.player != null && player == client.player;
    }

    public static boolean appliesTo(UUID uuid) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || uuid == null) return false;
        if (client.player != null && uuid.equals(client.player.getUUID())) return true;
        return client.getConnection() != null
                && uuid.equals(client.getConnection().getLocalGameProfile().id());
    }

    public static boolean appliesTo(PlayerInfo info) {
        if (info == null || !isEnabled()) return false;
        if (appliesTo(info.getProfile().id())) return true;
        String localName = realName();
        return !localName.isBlank() && info.getProfile().name().equalsIgnoreCase(localName);
    }

    public static Component replaceLocalPlayerName(Player player, Component original) {
        return appliesTo(player) ? replace(original, realName(), false) : original;
    }

    /** Replaces the real local name inside chat/tab components without flattening their styles. */
    public static Component replaceOwnName(Component original) {
        return isEnabled() ? replaceWithFlatFallback(original, realName(), false) : original;
    }

    /** Chat mentions use the animated text but omit private-use resource-pack icons. */
    public static Component replaceOwnNameInChat(Component original) {
        // Chat messages can contain arbitrary server IDs. Only replace a
        // structured component that represents the exact local name; never
        // flatten and animate a matching substring in the whole line.
        return isEnabled() ? replace(original, realName(), true) : original;
    }

    /** Standalone command entrypoints replacing Fabric's client command callback. */
    public static int commandStatus(Minecraft client) {
        return showStatus(client);
    }

    public static int commandReset(Minecraft client) {
        return reset(client);
    }

    public static int commandSet(Minecraft client, String name) {
        return set(client, name);
    }

    private static Component replace(
            Component original,
            String realName,
            boolean chatVariant
    ) {
        if (original == null || realName.isBlank() || realName.equals(VALUE.get())) {
            return original;
        }

        ComponentContents contents = original.getContents();
        ComponentContents replacedContents = contents;
        if (contents instanceof PlainTextContents plain) {
            String text = plain.text();
            if (chatVariant && !text.equalsIgnoreCase(realName)) {
                MutableComponent unchanged = MutableComponent.create(contents)
                        .setStyle(original.getStyle());
                for (Component sibling : original.getSiblings()) {
                    unchanged.append(replace(sibling, realName, true));
                }
                return unchanged;
            }
            if (indexOfIgnoreCase(text, realName, 0) >= 0) {
                MutableComponent replaced = Component.empty().setStyle(original.getStyle());
                int start = 0;
                int occurrence;
                while ((occurrence = indexOfIgnoreCase(text, realName, start)) >= 0) {
                    if (occurrence > start) {
                        replaced.append(Component.literal(text.substring(start, occurrence)));
                    }
                    replaced.append(chatVariant ? chatStyledValue() : styledValue());
                    start = occurrence + realName.length();
                }
                if (start < text.length()) {
                    replaced.append(Component.literal(text.substring(start)));
                }
                for (Component sibling : original.getSiblings()) {
                    replaced.append(replace(sibling, realName, chatVariant));
                }
                return replaced;
            }
        } else if (contents instanceof TranslatableContents translatable) {
            Object[] arguments = translatable.getArgs().clone();
            for (int index = 0; index < arguments.length; index++) {
                Object argument = arguments[index];
                if (argument instanceof Component component) {
                    arguments[index] = replace(component, realName, chatVariant);
                } else if (argument instanceof String text
                        && indexOfIgnoreCase(text, realName, 0) >= 0) {
                    arguments[index] = replace(
                            Component.literal(text), realName, chatVariant);
                }
            }
            replacedContents = new TranslatableContents(
                    translatable.getKey(), translatable.getFallback(), arguments);
        }

        MutableComponent replaced = MutableComponent.create(replacedContents)
                .setStyle(original.getStyle());
        for (Component sibling : original.getSiblings()) {
            replaced.append(replace(sibling, realName, chatVariant));
        }
        return replaced;
    }

    private static Component withoutImageGlyphs(Component component) {
        MutableComponent result = Component.empty();
        boolean leading = true;
        for (Component part : component.toFlatList()) {
            String filtered = part.getString().codePoints()
                    .filter(codePoint -> Character.getType(codePoint) != Character.PRIVATE_USE)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
            if (leading) {
                filtered = filtered.stripLeading();
                if (filtered.isEmpty()) {
                    continue;
                }
                leading = false;
            }
            result.append(Component.literal(filtered).setStyle(part.getStyle()));
        }
        return result;
    }

    private static Component replaceWithFlatFallback(
            Component original,
            String realName,
            boolean chatVariant
    ) {
        Component replaced = replace(original, realName, chatVariant);
        if (original == null || !replaced.getString().equals(original.getString())
                || indexOfIgnoreCase(original.getString(), realName, 0) < 0) {
            return replaced;
        }
        String text = original.getString();
        MutableComponent flattened = Component.empty().setStyle(original.getStyle());
        int start = 0;
        int occurrence;
        while ((occurrence = indexOfIgnoreCase(text, realName, start)) >= 0) {
            if (occurrence > start) flattened.append(text.substring(start, occurrence));
            flattened.append(chatVariant ? chatStyledValue() : styledValue());
            start = occurrence + realName.length();
        }
        if (start < text.length()) flattened.append(text.substring(start));
        return flattened;
    }

    private static boolean isAnimatedMarkup(String markup) {
        if (markup == null) return false;
        String lower = markup.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("<rainbow") || lower.contains("<pulse:")
                || lower.contains("<wave:") || lower.contains("<shine:")
                || lower.contains("<aurora") || lower.contains("<fire")
                || lower.contains("<sparkle:") || lower.contains("<chase:");
    }

    private static int indexOfIgnoreCase(String text, String target, int start) {
        if (target.isEmpty()) {
            return -1;
        }
        int limit = text.length() - target.length();
        for (int index = Math.max(0, start); index <= limit; index++) {
            if (text.regionMatches(true, index, target, 0, target.length())) {
                int before = index == 0 ? -1 : text.codePointBefore(index);
                int afterIndex = index + target.length();
                int after = afterIndex >= text.length() ? -1 : text.codePointAt(afterIndex);
                if (!isNameCharacter(before) && !isNameCharacter(after)) return index;
            }
        }
        return -1;
    }

    private static boolean isNameCharacter(int codePoint) {
        return codePoint >= 0 && (codePoint == '_' || Character.isLetterOrDigit(codePoint));
    }

    private static String realName() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            return client.getConnection().getLocalGameProfile().name();
        }
        return client.player == null ? "" : client.player.getGameProfile().name();
    }

    private static int set(Minecraft client, String raw) {
        String nickname = raw == null ? "" : raw.trim();
        if (nickname.isEmpty()) {
            ClientChat.send(client, "Nickname cannot be empty. Use .nickname reset.");
            return 0;
        }
        if (nickname.codePointCount(0, nickname.length()) > MAX_MARKUP_CODE_POINTS) {
            ClientChat.send(client, "Nickname markup is too long (maximum 512 characters).");
            return 0;
        }
        if (nickname.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            ClientChat.send(client, "Nickname cannot contain control characters.");
            return 0;
        }

        Component styled = DynamicMiniMessage.parse(nickname);
        String visible = styled.getString();
        if (visible.isBlank()) {
            ClientChat.send(client, "Nickname must contain visible text.");
            return 0;
        }
        if (visible.codePointCount(0, visible.length()) > MAX_CODE_POINTS) {
            ClientChat.send(client, "Nickname is too long (maximum 64 visible characters).");
            return 0;
        }

        VALUE.set(nickname);
        ClientChat.send(client, "Local nickname set to " + nickname
                + ". Only you can see this change.");
        return 1;
    }

    private static int reset(Minecraft client) {
        VALUE.set("");
        ClientChat.send(client, "Local nickname reset to your account name.");
        return 1;
    }

    private static int showStatus(Minecraft client) {
        ClientChat.send(client, isEnabled()
                ? "Local nickname: " + VALUE.get() + ". Usage: .nickname <name> or .nickname reset."
                : "Local nickname is disabled. Usage: .nickname <name>; [] and MiniMessage-style colors are supported.");
        return 1;
    }
}
