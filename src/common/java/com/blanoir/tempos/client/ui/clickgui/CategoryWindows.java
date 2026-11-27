package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.Widget;

/** The floating ClickGUI keeps its navigation window beside independently movable categories. */
public final class CategoryWindows implements GuiStyle {
    final Runnable hud, appearance, visibility, ysm, profiles;
    private FloatingDesktop desktop;

    CategoryWindows(Runnable hud, Runnable appearance, Runnable visibility, Runnable ysm, Runnable profiles) {
        this.hud = hud;
        this.appearance = appearance;
        this.visibility = visibility;
        this.ysm = ysm;
        this.profiles = profiles;
    }
    @Override public String id() { return "floating"; }
    @Override public Widget build(GuiModel model, Theme theme) {
        desktop = new FloatingDesktop(model, this);
        return desktop;
    }
    public void focusSearch() { desktop.main.beginSearch(); }
    public boolean endSearch() { return desktop.main.endSearch(); }
    float pageLeft(float width) { return desktop.pageLeft(width); }
    float pageTop(float height) { return Math.clamp(desktop.main.top(),0,Math.max(0,desktop.height()-height)); }
}
