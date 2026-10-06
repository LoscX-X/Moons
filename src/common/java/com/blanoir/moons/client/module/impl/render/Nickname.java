package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.text.DynamicMiniMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import java.util.UUID;
import java.util.regex.Pattern;

/** Local-only styled nickname and shuffled player-name rendering. */
public final class Nickname {
    private static final int MAX_CODE_POINTS = 64;
    private static final int MAX_MARKUP_CODE_POINTS = 512;
    private static final Pattern ANIMATED_MARKUP =
            Pattern.compile(
                    "<\\s*(?:rainbow|pulse|wave|shine|aurora|fire|sparkle|chase)(?=[:\\s>])",
                    Pattern.CASE_INSENSITIVE);
    private static final StringSetting VALUE =
            new StringSetting.Builder().name("nickname.value").defaultValue("").build();
    private static String cachedMarkup;
    private static boolean cachedAnimated;
    private static long renderFrame;
    private static long renderTimeMillis;
    private static long cachedRenderFrame = Long.MIN_VALUE;
    private static IChatComponent cachedStyledValue = new ChatComponentText("");
    private static final IChatComponent LIVE_CHAT_STYLED_VALUE = new ChatComponentText("");
    private static boolean chatStyledValueUsed;
    private static long nextChatRefreshMillis;

    private Nickname() {}

    public static void init() {
        NicknameShuffle.init();
        EventBus.FRAME.register(
                "Nickname.frame",
                event -> {
                    renderFrame++;
                    renderTimeMillis = System.nanoTime() / 1_000_000L;
                    if (isEnabled()) {
                        styledValue();
                        if (chatStyledValueUsed
                                && cachedAnimated
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

    public static synchronized IChatComponent styledValue() {
        String markup = VALUE.get();
        long frame = renderFrame;
        long timeMillis = frame == 0L ? System.nanoTime() / 1_000_000L : renderTimeMillis;
        boolean changed = !markup.equals(cachedMarkup);
        if (changed) cachedAnimated = isAnimatedMarkup(markup);
        if (changed || (cachedAnimated && frame != cachedRenderFrame)) {
            cachedMarkup = markup;
            cachedRenderFrame = frame;
            cachedStyledValue = DynamicMiniMessage.parse(markup, timeMillis);
            IChatComponent refreshedChatValue = withoutImageGlyphs(cachedStyledValue);
            LIVE_CHAT_STYLED_VALUE.getSiblings().clear();
            LIVE_CHAT_STYLED_VALUE.appendSibling(refreshedChatValue);
        }
        return cachedStyledValue;
    }

    public static synchronized IChatComponent chatStyledValue() {
        // styledValue refreshes both variants once for the current render frame.
        styledValue();
        chatStyledValueUsed = true;
        return LIVE_CHAT_STYLED_VALUE;
    }

    public static boolean appliesTo(EntityPlayer player) {
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        return isEnabled() && currentPlayer != null && player == currentPlayer;
    }

    public static boolean appliesTo(UUID uuid) {
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        if (!isEnabled() || uuid == null) return false;
        if (currentPlayer != null && uuid.equals(currentPlayer.getUniqueID())) return true;
        return connectionSnapshot != null && uuid.equals(client.getSession().getProfile().getId());
    }

    public static boolean appliesTo(NetworkPlayerInfo info) {
        if (info == null || !isEnabled()) return false;
        if (appliesTo(info.getGameProfile().getId())) return true;
        String localName = realName();
        return !localName.isBlank() && info.getGameProfile().getName().equalsIgnoreCase(localName);
    }

    public static IChatComponent replaceLocalPlayerName(
            EntityPlayer player, IChatComponent original) {
        String shuffled = player == null ? null : NicknameShuffle.name(player.getUniqueID());
        if (shuffled != null)
            return anonymize(original, player.getGameProfile().getName(), shuffled);
        return appliesTo(player) ? replace(original, realName(), false) : original;
    }

    public static IChatComponent replaceTabName(NetworkPlayerInfo info, IChatComponent original) {
        String shuffled = NicknameShuffle.name(info.getGameProfile().getId());
        if (shuffled != null) return anonymize(original, info.getGameProfile().getName(), shuffled);
        return appliesTo(info) ? replaceOwnName(original) : original;
    }

    private static IChatComponent anonymize(
            IChatComponent original, String realName, String alias) {
        IChatComponent replaced =
                replaceWithFlatFallback(original, realName, false, new ChatComponentText(alias));
        if (original != null
                && replaced.getUnformattedText().equals(original.getUnformattedText())
                && indexOfIgnoreCase(original.getUnformattedText(), alias, 0) < 0) {
            return new ChatComponentText(alias).setChatStyle(original.getChatStyle());
        }
        return replaced;
    }

    /** Replaces the real local name inside chat/tab components without flattening their styles. */
    public static IChatComponent replaceOwnName(IChatComponent original) {
        return isEnabled() ? replaceWithFlatFallback(original, realName(), false) : original;
    }

    /** Chat mentions use the animated text but omit private-use resource-pack icons. */
    public static IChatComponent replaceOwnNameInChat(IChatComponent original) {
        if (NicknameShuffle.isEnabled()) return NicknameShuffle.chat(original);
        // Chat messages can contain arbitrary server IDs. Only replace a
        // structured component that represents the exact local name; never
        // flatten and animate a matching substring in the whole line.
        return isEnabled() ? replace(original, realName(), true) : original;
    }

    private static IChatComponent replace(
            IChatComponent original, String realName, boolean chatVariant) {
        return replace(original, realName, chatVariant, null);
    }

    private static IChatComponent replace(
            IChatComponent original,
            String realName,
            boolean chatVariant,
            IChatComponent fixedValue) {
        if (original == null
                || realName.isBlank()
                || realName.equals(
                        fixedValue == null ? VALUE.get() : fixedValue.getUnformattedText())) {
            return original;
        }

        IChatComponent replaced;
        if (original instanceof ChatComponentText plain) {
            String text = plain.getChatComponentText_TextValue();
            replaced =
                    new ChatComponentText("")
                            .setChatStyle(original.getChatStyle().createShallowCopy());
            if (chatVariant && !text.equalsIgnoreCase(realName)) replaced.appendText(text);
            else {
                int start = 0, occurrence;
                while ((occurrence = indexOfIgnoreCase(text, realName, start)) >= 0) {
                    if (occurrence > start) replaced.appendText(text.substring(start, occurrence));
                    replaced.appendSibling(
                            fixedValue != null
                                    ? fixedValue
                                    : chatVariant ? chatStyledValue() : styledValue());
                    start = occurrence + realName.length();
                }
                if (start < text.length()) replaced.appendText(text.substring(start));
            }
        } else if (original instanceof ChatComponentTranslation translated) {
            Object[] args = translated.getFormatArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof IChatComponent part)
                    args[i] = replace(part, realName, chatVariant, fixedValue);
                else if (args[i] instanceof String text)
                    args[i] =
                            replace(new ChatComponentText(text), realName, chatVariant, fixedValue);
            }
            replaced =
                    new ChatComponentTranslation(translated.getKey(), args)
                            .setChatStyle(original.getChatStyle().createShallowCopy());
        } else {
            replaced = original.createCopy();
            replaced.getSiblings().clear();
        }
        for (IChatComponent sibling : original.getSiblings())
            replaced.appendSibling(replace(sibling, realName, chatVariant, fixedValue));
        return replaced;
    }

    private static IChatComponent withoutImageGlyphs(IChatComponent component) {
        IChatComponent result = new ChatComponentText("");
        boolean leading = true;
        for (IChatComponent part : component) {
            String filtered =
                    part.getUnformattedTextForChat()
                            .codePoints()
                            .filter(
                                    codePoint ->
                                            Character.getType(codePoint) != Character.PRIVATE_USE)
                            .collect(
                                    StringBuilder::new,
                                    StringBuilder::appendCodePoint,
                                    StringBuilder::append)
                            .toString();
            if (leading) {
                filtered = filtered.stripLeading();
                if (filtered.isEmpty()) {
                    continue;
                }
                leading = false;
            }
            result.appendSibling(new ChatComponentText(filtered).setChatStyle(part.getChatStyle()));
        }
        return result;
    }

    private static IChatComponent replaceWithFlatFallback(
            IChatComponent original, String realName, boolean chatVariant) {
        return replaceWithFlatFallback(original, realName, chatVariant, null);
    }

    private static IChatComponent replaceWithFlatFallback(
            IChatComponent original,
            String realName,
            boolean chatVariant,
            IChatComponent fixedValue) {
        IChatComponent replaced = replace(original, realName, chatVariant, fixedValue);
        if (original == null
                || !replaced.getUnformattedText().equals(original.getUnformattedText())
                || indexOfIgnoreCase(original.getUnformattedText(), realName, 0) < 0) {
            return replaced;
        }
        String text = original.getUnformattedText();
        IChatComponent flattened = new ChatComponentText("").setChatStyle(original.getChatStyle());
        int start = 0;
        int occurrence;
        while ((occurrence = indexOfIgnoreCase(text, realName, start)) >= 0) {
            if (occurrence > start) flattened.appendText(text.substring(start, occurrence));
            flattened.appendSibling(
                    fixedValue != null
                            ? fixedValue
                            : chatVariant ? chatStyledValue() : styledValue());
            start = occurrence + realName.length();
        }
        if (start < text.length()) flattened.appendText(text.substring(start));
        return flattened;
    }

    private static boolean isAnimatedMarkup(String markup) {
        return markup != null && ANIMATED_MARKUP.matcher(markup).find();
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
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        if (connectionSnapshot != null) {
            return client.getSession().getProfile().getName();
        }
        return currentPlayer == null ? "" : currentPlayer.getGameProfile().getName();
    }

    public static void commandSet(Minecraft client, String raw) {
        String nickname = raw == null ? "" : raw.trim();
        if (nickname.isEmpty()) {
            ClientChat.send(client, "Nickname cannot be empty. Use .nickname reset.");
            return;
        }
        if (nickname.codePointCount(0, nickname.length()) > MAX_MARKUP_CODE_POINTS) {
            ClientChat.send(client, "Nickname markup is too long (maximum 512 characters).");
            return;
        }
        if (nickname.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            ClientChat.send(client, "Nickname cannot contain control characters.");
            return;
        }

        IChatComponent styled = DynamicMiniMessage.parse(nickname);
        String visible = styled.getUnformattedText();
        if (visible.isBlank()) {
            ClientChat.send(client, "Nickname must contain visible text.");
            return;
        }
        if (visible.codePointCount(0, visible.length()) > MAX_CODE_POINTS) {
            ClientChat.send(client, "Nickname is too long (maximum 64 visible characters).");
            return;
        }

        VALUE.set(nickname);
        ClientChat.send(
                client, "Local nickname set to " + nickname + ". Only you can see this change.");
    }

    public static void commandReset(Minecraft client) {
        VALUE.set("");
        ClientChat.send(client, "已重置自己的昵称；全员混淆状态保持不变。");
    }

    public static void commandStatus(Minecraft client) {
        if (NicknameShuffle.isEnabled()) {
            ClientChat.send(
                    client,
                    "全员随机假名和皮肤混淆已开启。.nick all 重新混淆，.nick all reset 关闭，.nick reset 仅重置自己的昵称。");
            return;
        }
        ClientChat.send(
                client,
                isEnabled()
                        ? "Local nickname: "
                                + VALUE.get()
                                + ". Usage: .nick <name>, .nick all, .nick all reset, .nick reset."
                        : "Usage: .nick <name>, .nick all, .nick all reset, .nick reset; [] and MiniMessage-style colors are supported.");
    }
}
