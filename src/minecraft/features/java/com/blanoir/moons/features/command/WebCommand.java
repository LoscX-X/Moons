package com.blanoir.moons.features.command;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.web.RemoteConfigClient;

import net.minecraft.client.Minecraft;

/** Handles browser-based parameter and pairing commands. */
final class WebCommand {
    private WebCommand() {}

    static boolean handle(String tail) {
        Minecraft client = Minecraft.getInstance();
        String[] parts = tail.split("\\s+", 2);
        String operation = tail.isBlank() ? "status" : parts[0].toLowerCase(java.util.Locale.ROOT);
        switch (operation) {
            case "bind" -> {
                if (parts.length < 2) {
                    ClientChat.send(client, "Usage: .web bind <url>");
                } else {
                    try {
                        RemoteConfigClient.bind(parts[1]);
                        ClientChat.send(
                                client,
                                "Bound to "
                                        + RemoteConfigClient.uiUrl()
                                        + ". Future launches will connect automatically.");
                    } catch (IllegalArgumentException failure) {
                        ClientChat.send(client, "Web bind failed: " + failure.getMessage());
                    }
                }
            }
            case "status" -> status(client);
            case "connect" -> {
                if (requireBinding(client)) {
                    RemoteConfigClient.connect();
                    ClientChat.send(client, "Web connection started.");
                }
            }
            case "pair" -> {
                if (RemoteConfigClient.requestPairing()) {
                    ClientChat.send(client, "Requesting a new one-time pairing code...");
                } else {
                    requireBinding(client);
                }
            }
            case "disconnect" -> RemoteConfigClient.disconnect();
            case "unbind" -> {
                RemoteConfigClient.unbind();
                ClientChat.send(
                        client, "Web server binding removed. Automatic connection is disabled.");
            }
            default ->
                    ClientChat.send(
                            client, "Usage: .web <bind url|status|connect|pair|disconnect|unbind>");
        }
        return true;
    }

    private static void status(Minecraft client) {
        if (!requireBinding(client)) return;
        ClientChat.send(
                client,
                "Web remote control: "
                        + (RemoteConfigClient.isConnected() ? "connected" : "disconnected")
                        + ", UI: "
                        + RemoteConfigClient.uiUrl());
    }

    private static boolean requireBinding(Minecraft client) {
        if (RemoteConfigClient.hasBinding()) return true;
        ClientChat.send(client, "Web remote control is not bound. Usage: .web bind <url>");
        return false;
    }
}
