package com.blanoir.moons.features.command;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.module.impl.misc.aimdata.AimCollect;

import net.minecraft.client.Minecraft;

/** Local-only controls for packet-derived combat recordings. */
final class CollectCommand {
    private CollectCommand() {}

    static boolean handle(String tail) {
        Minecraft client = Minecraft.getInstance();
        String value = tail.trim();
        if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("off")) {
            AimCollect.setEnabled(client, value.equalsIgnoreCase("on"));
        } else {
            String[] parts = value.split("\\s+", 3);
            if (parts.length >= 2
                    && parts[0].equalsIgnoreCase("save")
                    && parts[1].equalsIgnoreCase("locate")) {
                if (parts.length == 3) {
                    String path = parts[2].trim();
                    if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\""))
                        path = path.substring(1, path.length() - 1);
                    try {
                        AimCollect.setDirectory(client, path);
                    } catch (IllegalArgumentException exception) {
                        ClientChat.send(
                                client, "Invalid dataset directory: " + exception.getMessage());
                    }
                } else ClientChat.send(client, "Collect directory: " + AimCollect.directory());
            } else {
                ClientChat.send(
                        client,
                        "Collect "
                                + (AimCollect.isEnabled() ? "on" : "off")
                                + " | "
                                + AimCollect.hudTag());
                ClientChat.send(
                        client,
                        "Usage: .collect on|off | .collect save locate [absolute directory]");
            }
        }
        return true;
    }
}
