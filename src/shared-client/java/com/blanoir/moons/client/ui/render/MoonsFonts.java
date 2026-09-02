package com.blanoir.moons.client.ui.render;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** Shared font style for the native ClickGUI and compact in-world labels. */
public final class MoonsFonts {
    /*
     * clickgui.json only referenced minecraft:default.  A Fabric host made the
     * moons:clickgui alias visible through its resource pack, but a hot-loaded
     * module JAR is intentionally not part of Minecraft's ResourceManager.
     * Styling text with that absent alias produces tofu boxes for every glyph.
     * Use the identical underlying vanilla font directly in standalone mode.
     */
    private static final Style CLICK_GUI_STYLE = Style.EMPTY;
    private static final Style MINECRAFT_PIXEL_STYLE = Style.EMPTY.withFont(
            new FontDescription.Resource(
                    Identifier.fromNamespaceAndPath("minecraft", "default")));

    private MoonsFonts() {
    }

    public static MutableComponent clickGuiText(String value) {
        return Component.literal(value).withStyle(CLICK_GUI_STYLE);
    }

    public static MutableComponent clickGuiText(String value, int color) {
        return Component.literal(value).withStyle(CLICK_GUI_STYLE.withColor(color));
    }

    public static MutableComponent minecraftPixelText(String value) {
        return Component.literal(value).withStyle(MINECRAFT_PIXEL_STYLE);
    }

}
