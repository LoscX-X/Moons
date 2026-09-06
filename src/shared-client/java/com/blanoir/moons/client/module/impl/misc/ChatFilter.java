package com.blanoir.moons.client.module.impl.misc;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Filters player-authored chat without depending on one server's text format. */
public final class ChatFilter {
    private static final String ENABLED_KEY = "chatfilter.enabled";
    private static final String MODE_KEY = "chatfilter.mode";
    private static final String DEFAULT_MODE = "smart";
    private static final List<String> MODES = List.of("native", "smart", "all_server");
    private static final int MAX_NAME_PREFIX = 128;
    private static final int MAX_SEPARATOR_DISTANCE = 64;

    private ChatFilter() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED_KEY, false);
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        Settings.setBoolean(ENABLED_KEY, enabled);
        ClientChat.send(client, "ChatFilter " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static List<String> modeOptions() {
        return MODES;
    }

    public static String mode() {
        String configured = normalizeMode(Settings.getString(MODE_KEY, DEFAULT_MODE));
        return MODES.contains(configured) ? configured : DEFAULT_MODE;
    }

    public static int setMode(Minecraft client, String value) {
        String normalized = normalizeMode(value);
        if (!MODES.contains(normalized)) {
            throw new IllegalArgumentException("ChatFilter mode must be native, smart, or all_server.");
        }
        Settings.setString(MODE_KEY, normalized);
        ClientChat.send(client, "ChatFilter mode set to " + normalized + ".");
        return 1;
    }

    public static String hudTag() {
        return switch (mode()) {
            case "native" -> "Native";
            case "all_server" -> "All Server";
            default -> "Smart";
        };
    }

    /** Native player chat already has a trustworthy source classification. */
    public static boolean shouldHideNativePlayerMessage() {
        return isEnabled();
    }

    /**
     * Plugin/proxy chat often arrives as a server-system component. Smart mode
     * classifies it using the live player list plus common structural chat
     * separators, rather than matching a server-specific rank prefix.
     */
    public static boolean shouldHideServerSystemMessage(Component message) {
        if (!isEnabled() || message == null) return false;
        return switch (mode()) {
            case "all_server" -> true;
            case "smart" -> looksLikePlayerChat(Minecraft.getInstance(), message.getString());
            default -> false;
        };
    }

    static boolean looksLikePlayerChat(Minecraft client, String rawText) {
        var connectionSnapshot = client == null ? null : client.getConnection();
        if (client == null || connectionSnapshot == null || rawText == null) return false;
        String text = normalizeText(rawText);
        if (text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);

        for (PlayerInfo info : connectionSnapshot.getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (name == null || name.isBlank()) continue;
            String loweredName = name.toLowerCase(Locale.ROOT);
            int from = 0;
            while (from < lower.length()) {
                int index = lower.indexOf(loweredName, from);
                if (index < 0 || index > MAX_NAME_PREFIX) break;
                int end = index + loweredName.length();
                if (hasNameBoundaries(lower, index, end)
                        && hasChatSeparator(text, end)) {
                    return true;
                }
                from = index + 1;
            }
        }
        return false;
    }

    private static boolean hasNameBoundaries(String text, int start, int end) {
        return (start == 0 || !isUsernameCharacter(text.charAt(start - 1)))
                && (end == text.length() || !isUsernameCharacter(text.charAt(end)));
    }

    private static boolean isUsernameCharacter(char value) {
        return value == '_' || value >= '0' && value <= '9'
                || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z';
    }

    private static boolean hasChatSeparator(String text, int nameEnd) {
        int limit = Math.min(text.length(), nameEnd + MAX_SEPARATOR_DISTANCE);
        for (int index = nameEnd; index < limit; index++) {
            char value = text.charAt(index);
            if (value == '\n' || value == '\r') return false;
            if (value == ':' || value == '\uff1a'
                    || value == '>' || value == '\u00bb' || value == '\u203a'
                    || value == '\u27a4' || value == '\u279c' || value == '\u27f6') {
                return true;
            }
            // Covers formats such as "[Player] message" without treating an
            // unrelated closing rank bracket later in the line as chat.
            if (value == ']' && index - nameEnd <= 1) return true;
        }
        return false;
    }

    private static String normalizeText(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizeMode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }
}
