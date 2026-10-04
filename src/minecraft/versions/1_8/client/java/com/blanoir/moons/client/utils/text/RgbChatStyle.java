package com.blanoir.moons.client.utils.text;

import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

/** Retains full RGB for Moons text; vanilla text falls back to the closest 1.8 palette color. */
public final class RgbChatStyle extends ChatStyle {
    private final int rgb;

    private RgbChatStyle(int rgb) {
        this.rgb = rgb & 0xffffff;
        setColor(nearest(rgb));
    }

    public int rgb() {
        return rgb;
    }

    public static RgbChatStyle from(ChatStyle source, int rgb) {
        var target = new RgbChatStyle(rgb);
        target.setBold(source.getBold());
        target.setItalic(source.getItalic());
        target.setUnderlined(source.getUnderlined());
        target.setStrikethrough(source.getStrikethrough());
        target.setObfuscated(source.getObfuscated());
        target.setChatClickEvent(source.getChatClickEvent());
        target.setChatHoverEvent(source.getChatHoverEvent());
        target.setInsertion(source.getInsertion());
        return target;
    }

    @Override
    public ChatStyle createShallowCopy() {
        return from(this, rgb);
    }

    @Override
    public ChatStyle createDeepCopy() {
        return from(this, rgb);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RgbChatStyle color && rgb == color.rgb && super.equals(other);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + rgb;
    }

    public static int palette(int index) {
        int base = (index >> 3 & 1) * 85;
        int r = (index >> 2 & 1) * 170 + base,
                g = (index >> 1 & 1) * 170 + base,
                b = (index & 1) * 170 + base;
        if (index == 6) r += 85;
        return r << 16 | g << 8 | b;
    }

    private static EnumChatFormatting nearest(int rgb) {
        int best = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 16; i++) {
            int color = palette(i),
                    r = (rgb >> 16 & 255) - (color >> 16 & 255),
                    g = (rgb >> 8 & 255) - (color >> 8 & 255),
                    b = (rgb & 255) - (color & 255);
            double d = r * r * .3 + g * g * .59 + b * b * .11;
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return EnumChatFormatting.values()[best];
    }
}
