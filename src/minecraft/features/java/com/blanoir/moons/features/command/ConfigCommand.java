package com.blanoir.moons.features.command;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.ConfigProfiles;

import net.minecraft.client.Minecraft;

import java.util.Locale;

/** .config create/save/load/list, backed by the same presets shown in ClickGUI. */
final class ConfigCommand {
    private ConfigCommand() {}

    static boolean handle(String tail) {
        Minecraft client = Minecraft.getInstance();
        String[] parts = tail.trim().split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String name = parts.length > 1 ? parts[1].trim() : ConfigProfiles.selected();
        try {
            switch (action) {
                case "create" -> {
                    if (parts.length < 2)
                        throw new IllegalArgumentException("用法：.config create <名称>");
                    ConfigProfiles.create(name);
                    ClientChat.send(client, "已创建配置：" + name);
                }
                case "save" -> {
                    ConfigProfiles.save(name);
                    ClientChat.send(client, "已保存配置：" + name);
                }
                case "load" -> {
                    ConfigProfiles.load(name);
                    ClientChat.send(client, "已加载配置：" + name);
                }
                case "list" ->
                        ClientChat.send(
                                client, "Configs: " + String.join(", ", ConfigProfiles.list()));
                default ->
                        ClientChat.send(
                                client, "用法：.config create <名称> | save [名称] | load <名称> | list");
            }
        } catch (Exception failure) {
            ClientChat.send(client, "Config: " + failure.getMessage());
        }
        return true;
    }
}
