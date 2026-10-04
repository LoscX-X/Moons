package com.blanoir.moons.client.command;

import com.blanoir.moons.client.chat.ClientChat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.event.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.util.*;

import java.util.*;

/** Local held-item NBT inspector. Uses 1.8's actual ItemStack serialization. */
public final class NbtParserCommand {
    private static final int MAX_LINES = 180, MAX_DEPTH = 16;
    private static String copyToken = "", copyText = "";

    private NbtParserCommand() {}

    public static boolean handle(String arguments) {
        Minecraft client = Minecraft.getMinecraft();
        String args = arguments == null ? "" : arguments.trim();
        if (args.startsWith("copy ")) {
            if (!copyToken.isEmpty() && args.substring(5).equals(copyToken)) {
                GuiScreen.setClipboardString(copyText);
                ClientChat.send(client, "NBT Parser: 已复制完整 SNBT。");
            }
            return true;
        }
        if (!args.isEmpty()) {
            ClientChat.send(client, "用法: .nbtparser");
            return true;
        }
        if (client.thePlayer == null || client.theWorld == null) {
            ClientChat.send(client, "NBT Parser: 当前没有进入世界。");
            return true;
        }
        ItemStack stack = client.thePlayer.getHeldItem();
        if (stack == null || stack.stackSize <= 0) {
            ClientChat.send(client, "NBT Parser: 请先在主手拿着一个物品。");
            return true;
        }
        NBTTagCompound root = stack.writeToNBT(new NBTTagCompound());
        copyText = root.toString();
        copyToken = UUID.randomUUID().toString();
        ClientChat.send(client, text("━━━━━━━━ NBT Parser ━━━━━━━━", EnumChatFormatting.AQUA));
        ClientChat.send(
                client,
                text(
                        "物品  " + stack.getDisplayName() + "  ×" + stack.stackSize,
                        EnumChatFormatting.GOLD));
        IChatComponent copy = text("[ 点击复制完整 SNBT ]", EnumChatFormatting.GREEN);
        copy.getChatStyle()
                .setBold(true)
                .setChatClickEvent(
                        new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND, ".nbtparser copy " + copyToken))
                .setChatHoverEvent(
                        new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                text(
                                        "复制 " + copyText.length() + " 个字符到剪贴板",
                                        EnumChatFormatting.YELLOW)));
        ClientChat.send(client, copy);
        List<IChatComponent> lines = new ArrayList<>();
        int[] omitted = {0};
        appendTag(lines, root, 0, null, true, omitted);
        lines.forEach(line -> ClientChat.send(client, line));
        if (omitted[0] > 0)
            ClientChat.send(
                    client,
                    text(
                            "… 已省略 " + omitted[0] + " 个节点，点击上方按钮可复制完整数据。",
                            EnumChatFormatting.DARK_GRAY));
        return true;
    }

    private static void appendTag(
            List<IChatComponent> output,
            NBTBase tag,
            int depth,
            String key,
            boolean last,
            int[] omitted) {
        if (output.size() >= MAX_LINES) {
            omitted[0]++;
            return;
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < depth; i++)
            prefix.append(i == depth - 1 ? (last ? "└─ " : "├─ ") : "│  ");
        IChatComponent line = text(prefix.toString(), EnumChatFormatting.DARK_GRAY);
        if (key != null)
            line.appendSibling(text(key, EnumChatFormatting.AQUA))
                    .appendSibling(text(": ", EnumChatFormatting.DARK_GRAY));
        if (depth > MAX_DEPTH) {
            output.add(line.appendSibling(text("… 深度过大", EnumChatFormatting.DARK_GRAY)));
            return;
        }
        if (tag instanceof NBTTagCompound compound) {
            List<String> keys = compound.getKeySet().stream().sorted().toList();
            output.add(
                    line.appendSibling(text(keys.isEmpty() ? "{}" : "{", EnumChatFormatting.GRAY)));
            for (int i = 0; i < keys.size(); i++)
                appendTag(
                        output,
                        compound.getTag(keys.get(i)),
                        depth + 1,
                        keys.get(i),
                        i == keys.size() - 1,
                        omitted);
            if (!keys.isEmpty() && output.size() < MAX_LINES)
                output.add(text(prefix + "}", EnumChatFormatting.GRAY));
            return;
        }
        if (tag instanceof NBTTagList list) {
            output.add(
                    line.appendSibling(
                            text("[ " + list.tagCount() + " 项 ]", EnumChatFormatting.DARK_AQUA)));
            for (int i = 0; i < list.tagCount(); i++)
                appendTag(
                        output, list.get(i), depth + 1, "#" + i, i == list.tagCount() - 1, omitted);
            return;
        }
        EnumChatFormatting color =
                switch (tag.getId()) {
                    case 8 -> EnumChatFormatting.GREEN;
                    case 1, 2, 3, 4, 5, 6 -> EnumChatFormatting.GOLD;
                    case 7, 11 -> EnumChatFormatting.LIGHT_PURPLE;
                    default -> EnumChatFormatting.WHITE;
                };
        output.add(line.appendSibling(text(tag.toString(), color)));
    }

    private static IChatComponent text(String value, EnumChatFormatting color) {
        return new ChatComponentText(value).setChatStyle(new ChatStyle().setColor(color));
    }
}
