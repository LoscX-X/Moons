package com.blanoir.moons.client.command;

import com.blanoir.moons.client.chat.ClientChat;
import com.mojang.serialization.DataResult;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Pretty, local-only inspector for the held ItemStack's serialized NBT/components. */
public final class NbtParserCommand {
    private static final int MAX_LINES = 180;
    private static final int MAX_DEPTH = 16;

    private NbtParserCommand() { }

    public static boolean handle(String arguments) {
        Minecraft client = Minecraft.getInstance();
        if (arguments != null && !arguments.isBlank()) {
            ClientChat.send(client, Component.literal("用法: .nbtparser")
                    .withStyle(ChatFormatting.RED));
            return true;
        }
        if (client.player == null || client.level == null) {
            ClientChat.send(client, Component.literal("NBT Parser: 当前没有进入世界。")
                    .withStyle(ChatFormatting.RED));
            return true;
        }

        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) {
            ClientChat.send(client, Component.literal("NBT Parser: 请先在主手拿着一个物品。")
                    .withStyle(ChatFormatting.YELLOW));
            return true;
        }

        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
                client.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
        Tag root = encoded.result().orElse(null);
        if (root == null) {
            String reason = encoded.error().map(error -> error.message()).orElse("未知编码错误");
            ClientChat.send(client, Component.literal("NBT Parser: " + reason)
                    .withStyle(ChatFormatting.RED));
            return true;
        }

        String snbt = root.toString();
        MutableComponent title = Component.literal("━━━━━━━━ ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("NBT Parser").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" ━━━━━━━━").withStyle(ChatFormatting.DARK_GRAY));
        ClientChat.send(client, title);
        ClientChat.send(client, Component.literal("物品  ").withStyle(ChatFormatting.GRAY)
                .append(stack.getDisplayName().copy())
                .append(Component.literal("  ×" + stack.getCount()).withStyle(ChatFormatting.GOLD)));

        MutableComponent copy = Component.literal("[ 点击复制完整 SNBT ]")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(snbt))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "复制 " + snbt.length() + " 个字符到剪贴板")
                                .withStyle(ChatFormatting.YELLOW))));
        ClientChat.send(client, copy);

        List<Component> lines = new ArrayList<>();
        appendTag(lines, root, 0, null, true);
        int visible = Math.min(lines.size(), MAX_LINES);
        for (int index = 0; index < visible; index++) {
            ClientChat.send(client, lines.get(index));
        }
        if (lines.size() > MAX_LINES) {
            ClientChat.send(client, Component.literal("… 已省略 " + (lines.size() - MAX_LINES)
                    + " 行，点击上方按钮可复制完整数据。")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
        ClientChat.send(client, Component.literal("━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_GRAY));
        return true;
    }

    private static void appendTag(
            List<Component> output,
            Tag tag,
            int depth,
            String key,
            boolean last
    ) {
        if (depth > MAX_DEPTH) {
            output.add(prefix(depth, last).append(Component.literal("… 深度过大")
                    .withStyle(ChatFormatting.DARK_GRAY)));
            return;
        }
        if (tag instanceof CompoundTag compound) {
            MutableComponent line = prefix(depth, last);
            appendKey(line, key);
            line.append(Component.literal("{").withStyle(ChatFormatting.GRAY));
            if (compound.isEmpty()) {
                line.append(Component.literal("}").withStyle(ChatFormatting.GRAY));
            }
            output.add(line);
            List<Map.Entry<String, Tag>> entries = compound.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .toList();
            for (int index = 0; index < entries.size(); index++) {
                Map.Entry<String, Tag> entry = entries.get(index);
                appendTag(output, entry.getValue(), depth + 1, entry.getKey(),
                        index == entries.size() - 1);
            }
            if (!compound.isEmpty()) {
                output.add(prefix(depth, true).append(Component.literal("}")
                        .withStyle(ChatFormatting.GRAY)));
            }
            return;
        }
        if (tag instanceof ListTag list) {
            MutableComponent line = prefix(depth, last);
            appendKey(line, key);
            line.append(Component.literal("[ ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(list.size() + " 项").withStyle(ChatFormatting.DARK_AQUA))
                    .append(Component.literal(" ]").withStyle(ChatFormatting.GRAY));
            output.add(line);
            for (int index = 0; index < list.size(); index++) {
                appendTag(output, list.get(index), depth + 1, "#" + index,
                        index == list.size() - 1);
            }
            return;
        }

        MutableComponent line = prefix(depth, last);
        appendKey(line, key);
        String value = tag.toString();
        ChatFormatting color = switch (tag.getId()) {
            case Tag.TAG_STRING -> ChatFormatting.GREEN;
            case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG,
                    Tag.TAG_FLOAT, Tag.TAG_DOUBLE -> ChatFormatting.GOLD;
            case Tag.TAG_BYTE_ARRAY, Tag.TAG_INT_ARRAY, Tag.TAG_LONG_ARRAY -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.WHITE;
        };
        line.append(Component.literal(value).withStyle(color));
        output.add(line);
    }

    private static MutableComponent prefix(int depth, boolean last) {
        MutableComponent result = Component.empty();
        for (int index = 0; index < depth; index++) {
            result.append(Component.literal(index == depth - 1
                    ? (last ? "└─ " : "├─ ") : "│  ")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return result;
    }

    private static void appendKey(MutableComponent line, String key) {
        if (key == null) return;
        line.append(Component.literal(key).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY));
    }
}
