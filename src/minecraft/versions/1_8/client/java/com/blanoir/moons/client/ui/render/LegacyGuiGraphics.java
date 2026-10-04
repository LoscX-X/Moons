package com.blanoir.moons.client.ui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.IChatComponent;

/** Immediate 1.8 HUD drawing surface; all coordinates are scaled GUI pixels. */
public final class LegacyGuiGraphics {
    private final Minecraft client;

    public LegacyGuiGraphics(Minecraft client) {
        this.client = client;
    }

    public int guiWidth() {
        return new ScaledResolution(client).getScaledWidth();
    }

    public int guiHeight() {
        return new ScaledResolution(client).getScaledHeight();
    }

    public void fill(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, bottom, color);
    }

    public void text(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        font.drawString(text, x, y, color, shadow);
    }

    public void text(
            FontRenderer font, IChatComponent text, int x, int y, int color, boolean shadow) {
        float cursor = x;
        for (IChatComponent part : text) {
            var style = part.getChatStyle();
            String value =
                    style.getFormattingCode().replaceAll("(?i)\u00a7[0-9a-f]", "")
                            + part.getUnformattedTextForChat();
            int rgb =
                    style instanceof com.blanoir.moons.client.utils.text.RgbChatStyle exact
                            ? exact.rgb()
                            : style.getColor() == null
                                    ? color & 0xffffff
                                    : com.blanoir.moons.client.utils.text.RgbChatStyle.palette(
                                            style.getColor().getColorIndex());
            font.drawString(value, cursor, y, color & 0xff000000 | rgb, shadow);
            cursor += font.getStringWidth(value);
        }
    }
}
