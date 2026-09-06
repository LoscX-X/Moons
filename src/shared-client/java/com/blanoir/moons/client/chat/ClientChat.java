package com.blanoir.moons.client.chat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.config.Settings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ClientChat {
    private static final String ENABLED_KEY = "clientchat.prefixEnabled";
    private static final ThreadLocal<Integer> SILENCE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ClientChat() {
    }

    public static void send(Minecraft client, String message) {
        if (client != null && SILENCE_DEPTH.get() == 0 && !isToggleNotice(message)) {
            MinecraftClientAccess.sendSystemMessage(client, decorate(Component.literal(normalize(message))), false);
        }
    }

    public static void send(Minecraft client, Component message) {
        if (client != null && message != null && SILENCE_DEPTH.get() == 0) {
            MinecraftClientAccess.sendSystemMessage(client, decorate(message), false);
        }
    }

    private static MutableComponent decorate(Component message) {
        if (!isPrefixEnabled()) {
            return message.copy();
        }
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(prefix()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal("] › ").withStyle(ChatFormatting.DARK_GRAY))
                .append(message.copy().withStyle(ChatFormatting.GRAY));
    }

    public static boolean isPrefixEnabled() {
        return Settings.getBoolean(ENABLED_KEY, true);
    }

    public static String prefix() {
        return ClientBranding.name();
    }

    public static int setPrefixEnabled(Minecraft ignoredClient, boolean enabled) {
        Settings.setBoolean(ENABLED_KEY, enabled);
        return 1;
    }

    public static int setPrefix(Minecraft ignoredClient, String value) {
        return ClientBranding.setName(value == null ? "" : value.replace('§', '&')) ? 1 : 0;
    }

    private static String normalize(String message) {
        return message == null ? "" : message.strip();
    }

    private static boolean isToggleNotice(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.matches(".*\\b(enabled|disabled)\\b.*");
    }

    public static void actionBar(Minecraft client, String message) {
        if (client != null && SILENCE_DEPTH.get() == 0) {
            MinecraftClientAccess.sendSystemMessage(client, decorate(Component.literal(normalize(message))), true);
        }
    }

    /** Runs GUI-originated mutations without turning sliders into chat spam. */
    public static void withoutNoticesOnCurrentThread(Runnable action) {
        SILENCE_DEPTH.set(SILENCE_DEPTH.get() + 1);
        try {
            action.run();
        } finally {
            int depth = SILENCE_DEPTH.get() - 1;
            if (depth <= 0) {
                SILENCE_DEPTH.remove();
            } else {
                SILENCE_DEPTH.set(depth);
            }
        }
    }
}
