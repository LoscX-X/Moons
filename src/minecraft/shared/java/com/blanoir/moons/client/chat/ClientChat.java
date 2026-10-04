package com.blanoir.moons.client.chat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.config.Settings;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

public final class ClientChat {
    private static final String ENABLED_KEY = "clientchat.prefixEnabled";
    private static final ThreadLocal<Integer> SILENCE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ClientChat() {}

    public static void send(Minecraft client, String message) {
        if (client != null && SILENCE_DEPTH.get() == 0 && !isToggleNotice(message)) {
            MinecraftClientAccess.sendSystemMessage(
                    client, decorate(new ChatComponentText(normalize(message))), false);
        }
    }

    public static void send(Minecraft client, IChatComponent message) {
        if (client != null && message != null && SILENCE_DEPTH.get() == 0) {
            MinecraftClientAccess.sendSystemMessage(client, decorate(message), false);
        }
    }

    private static IChatComponent decorate(IChatComponent message) {
        if (!isPrefixEnabled()) {
            return message.createCopy();
        }
        IChatComponent result =
                new ChatComponentText("[")
                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
        result.appendSibling(
                new ChatComponentText(prefix())
                        .setChatStyle(
                                new ChatStyle().setColor(EnumChatFormatting.AQUA).setBold(true)));
        result.appendSibling(
                new ChatComponentText("] › ")
                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY)));
        IChatComponent body = message.createCopy();
        if (body.getChatStyle().getColor() == null)
            body.getChatStyle().setColor(EnumChatFormatting.GRAY);
        return result.appendSibling(body);
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
            MinecraftClientAccess.sendSystemMessage(
                    client, decorate(new ChatComponentText(normalize(message))), true);
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
