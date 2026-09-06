package com.blanoir.moons.features.command;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import java.util.Locale;

/** Command front end for the same persistent bindings used by ClickGUI. */
public final class BindCommand {
    private BindCommand() { }

    public static boolean handle(String tail) {
        Minecraft client = Minecraft.getInstance();
        String[] parts = tail.trim().split("\\s+");
        if (tail.isBlank() || parts.length != 2) {
            ClientChat.send(client, "用法：.bind <功能ID> <按键|none>，例如 .bind scaffold r");
            return true;
        }
        ModuleRegistry.Module module = ModuleRegistry.modules().stream()
                .filter(entry -> entry.id().equalsIgnoreCase(parts[0])
                        || entry.name().equalsIgnoreCase(parts[0]))
                .findFirst().orElse(null);
        if (module == null) {
            ClientChat.send(client, "未知功能：" + parts[0]);
            return true;
        }
        if (parts[1].equalsIgnoreCase("none")) {
            ModuleKeybinds.unbind(module.id());
            ClientChat.send(client, module.name() + (module.id().equals("clickgui")
                    ? " 的菜单按键已恢复默认 Right Shift" : " 已取消绑定"));
            return true;
        }
        InputConstants.Key key = parseKey(parts[1]);
        if (!ModuleKeybinds.isValid(key)) {
            ClientChat.send(client, "未知按键：" + parts[1] + "（支持 R、F6、RSHIFT、MOUSE4 等）");
        } else if (!ModuleKeybinds.bind(module.id(), key)) {
            ClientChat.send(client, "绑定失败：该按键保留给菜单使用");
        } else {
            ClientChat.send(client, module.name() + " 已绑定 " + parts[1].toUpperCase(Locale.ROOT));
        }
        return true;
    }

    static InputConstants.Key parseKey(String raw) {
        String name = raw.toLowerCase(Locale.ROOT);
        name = switch (name) {
            case "rshift" -> "right.shift";
            case "lshift", "shift" -> "left.shift";
            case "rctrl", "rcontrol" -> "right.control";
            case "lctrl", "lcontrol", "ctrl", "control" -> "left.control";
            case "ralt" -> "right.alt";
            case "lalt", "alt" -> "left.alt";
            case "esc" -> "escape";
            case "del" -> "delete";
            case "ins" -> "insert";
            case "pgup" -> "page.up";
            case "pgdn" -> "page.down";
            case "mouse1" -> "key.mouse.left";
            case "mouse2" -> "key.mouse.right";
            case "mouse3" -> "key.mouse.middle";
            default -> name.matches("mouse[4-8]") ? "key.mouse." + name.substring(5) : name;
        };
        if (!name.startsWith("key.")) name = "key.keyboard." + name;
        if (!name.startsWith("key.keyboard.") && !name.startsWith("key.mouse.")) return InputConstants.UNKNOWN;
        try {
            InputConstants.Key key = InputConstants.getKey(name);
            int maximum = name.startsWith("key.mouse.") ? 7 : 348;
            return ModuleKeybinds.isValid(key) && key.getValue() <= maximum ? key : InputConstants.UNKNOWN;
        } catch (RuntimeException ignored) {
            return InputConstants.UNKNOWN;
        }
    }
}
