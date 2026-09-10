package com.blanoir.moons.features.command;

import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.command.NbtParserCommand;
import com.blanoir.moons.client.command.PremiumCheckCommand;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.render.Nickname;
import com.blanoir.moons.client.ui.clickgui.ModuleGui;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;

import java.util.Locale;

/** Routes local client commands without depending on a loader command API. */
public final class ClientCommands {
    private ClientCommands() {}

    public static boolean handle(String raw) {
        String command = raw == null ? "" : raw.trim();
        if (!command.startsWith(".")) return false;
        command = command.substring(1).trim();
        if (command.isEmpty()) {
            ClientChat.send(Minecraft.getInstance(), "未知指令: .");
            return true;
        }
        String[] root = command.split("\\s+", 2);
        String tail = root.length == 2 ? root[1].trim() : "";
        return switch (root[0].toLowerCase(Locale.ROOT)) {
            case "moons" -> handleMoons(tail);
            case "team" -> handleTeam(tail);
            case "bind" -> BindCommand.handle(tail);
            case "config" -> ConfigCommand.handle(tail);
            case "unload" -> unload();
            case "xray" -> XrayCommand.handle(tail);
            case "web" -> WebCommand.handle(tail);
            case "taboutput" -> TabOutputCommand.handle(tail);
            case "nbtparser" -> NbtParserCommand.handle(tail);
            case "nickname", "nick" -> handleNickname(tail);
            default -> {
                ClientChat.send(Minecraft.getInstance(), "未知指令: ." + root[0]);
                yield true;
            }
        };
    }

    private static boolean unload() {
        ClientChat.send(Minecraft.getInstance(), "正在卸载 Moons…");
        AgentBridge.requestUnload();
        return true;
    }

    private static boolean handleTeam(String tail) {
        Minecraft client = Minecraft.getInstance();
        if (tail.equalsIgnoreCase("on") || tail.equalsIgnoreCase("off")) {
            Targeting.setTeamCheckEnabled(client, tail.equalsIgnoreCase("on"));
        } else if (!tail.isEmpty()) {
            ClientChat.send(client, "用法：.team on|off");
            return true;
        }
        ClientChat.send(client, "组队检测：" + (Targeting.isTeamCheckEnabled() ? "开启" : "关闭"));
        return true;
    }

    private static boolean handleNickname(String tail) {
        Minecraft client = Minecraft.getInstance();
        if (tail.isEmpty()) {
            Nickname.commandStatus(client);
        } else if (tail.equalsIgnoreCase("reset")) {
            Nickname.commandReset(client);
        } else {
            Nickname.commandSet(client, tail);
        }
        return true;
    }

    private static boolean handleMoons(String tail) {
        Minecraft client = Minecraft.getInstance();
        if (tail.isEmpty()) {
            ModuleGui.toggle(client);
            return true;
        }
        if (tail.equalsIgnoreCase("premiumcheck all")) {
            PremiumCheckCommand.checkAllTabPlayers(client);
            return true;
        }
        String[] parts = tail.split("\\s+", 3);
        if (parts.length == 1 && parts[0].equalsIgnoreCase("list")) {
            String enabled =
                    ModuleRegistry.enabledModules().stream()
                            .map(ModuleRegistry.Module::displayText)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("none");
            ClientChat.send(client, "Enabled modules: " + enabled);
            return true;
        }
        if (parts.length < 2) {
            boolean known =
                    ModuleRegistry.modules().stream()
                            .anyMatch(candidate -> candidate.id().equalsIgnoreCase(parts[0]));
            ClientChat.send(
                    client,
                    known
                            ? "Usage: .moons <module> <enable|disable|toggle|setting value>"
                            : "未知指令: .moons " + parts[0]);
            return true;
        }
        String module = parts[0];
        String operation = parts[1].toLowerCase(Locale.ROOT);
        if (operation.equals("enable")
                || operation.equals("disable")
                || operation.equals("toggle")) {
            ModuleRegistry.Module found =
                    ModuleRegistry.modules().stream()
                            .filter(candidate -> candidate.id().equalsIgnoreCase(module))
                            .findFirst()
                            .orElse(null);
            if (found == null) {
                ClientChat.send(client, "Unknown module: " + module);
            } else {
                boolean enabled =
                        operation.equals("toggle")
                                ? !found.enabled().getAsBoolean()
                                : operation.equals("enable");
                report(client, ModuleRegistry.setEnabled(found.id(), enabled));
            }
            return true;
        }
        if (parts.length < 3) {
            ClientChat.send(client, "Missing value for " + module + "." + operation);
            return true;
        }
        report(client, ModuleRegistry.setValue(module, operation, parse(parts[2])));
        return true;
    }

    private static JsonElement parse(String raw) {
        String value = raw.trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(value));
        }
        if (value.matches("-?\\d+(?:\\.\\d+)?--?\\d+(?:\\.\\d+)?")) {
            int split = value.indexOf('-', 1);
            JsonArray range = new JsonArray();
            range.add(Double.parseDouble(value.substring(0, split)));
            range.add(Double.parseDouble(value.substring(split + 1)));
            return range;
        }
        try {
            return new JsonPrimitive(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
            return new JsonPrimitive(value);
        }
    }

    private static void report(Minecraft client, com.google.gson.JsonObject result) {
        if (!result.get("ok").getAsBoolean()) {
            ClientChat.send(client, result.get("error").getAsString());
        }
    }
}
