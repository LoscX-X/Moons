package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.ui.UiScreen;
import com.blanoir.tempos.client.ui.clickgui.ThemePreferences;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** Shared theme controls, hosted in the game and returning to the screen that opened them. */
public final class AppearanceScreen extends UiScreen {
    private final Screen previous;
    private final ThemePreferences prefs;

    public static AppearanceScreen create(Minecraft client, Screen previous) {
        WidgetTree tree = new WidgetTree(clipboard(client));
        return new AppearanceScreen(client, previous, tree, new Theme());
    }
    private AppearanceScreen(Minecraft client, Screen previous, WidgetTree tree, Theme theme) {
        super("Theme", tree, theme);
        this.previous = previous;
        prefs = new ThemePreferences(theme);
        int w = (int)(client.getWindow().getGuiScaledWidth()/contentScale());
        tree.root.add(new ThemeFrame(prefs,this::onClose,12,18,Math.min(202,Math.max(100,w-24))));
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);
        prefs.saveNumbers();
        return handled;
    }
    @Override protected float fontSize() { return Theme.Floating.TEXT_SIZE; }
    @Override protected float contentScale() { return .85f; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        boolean handled = super.mouseScrolled(x, y, horizontal, vertical);
        prefs.saveNumbers();
        return handled;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        boolean handled = super.keyPressed(event);
        prefs.saveNumbers();
        return handled;
    }
    @Override public void removed() {
        super.removed();
        prefs.saveNumbers();
    }
    @Override public void onClose() { MinecraftClientAccess.setScreen(Minecraft.getInstance(), previous); }
}
