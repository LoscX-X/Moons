package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.config.ClientBranding;
import com.blanoir.tempos.client.config.ConfigProfiles;
import com.blanoir.tempos.client.ui.UiScreen;
import com.blanoir.tempos.client.ui.host.UiHost;
import com.blanoir.tempos.client.ui.pages.ThemeFrame;
import com.blanoir.tempos.client.ui.pages.ConfigProfilesFrame;
import com.blanoir.tempos.client.ui.pages.ModuleVisibilityFrame;
import com.blanoir.tempos.client.ui.pages.YsmSelectorFrame;
import com.blanoir.tempos.client.ui.widget.Window;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.Anim;
import com.blanoir.tempos.client.ui.widget.Widget;
import com.blanoir.tempos.client.ui.widget.WidgetTree;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** Minecraft owns the screen and its input. Categories keep their state when a secondary page returns. */
public final class CategoryScreen extends UiScreen {
    private final GuiModel model = new GuiModel();
    private final ThemePreferences prefs;
    private final CategoryWindows style;
    private final Widget desktop;
    private Window secondary;
    private final Anim fade = new Anim(0);
    private boolean closing, suppressBoundCharacter;
    private long parameterEpoch = -1;

    public static CategoryScreen create(Minecraft client) {
        return new CategoryScreen(new WidgetTree(clipboard(client)),new Theme());
    }
    private CategoryScreen(WidgetTree tree,Theme theme) {
        super(ClientBranding.name(),tree,theme);
        prefs = new ThemePreferences(theme);
        style = new CategoryWindows(() -> UiHost.open(UiHost.Page.HUD_EDITOR),this::appearance,this::visibility,
                this::ysmModels, this::profiles);
        desktop = style.build(model,theme);
        tree.root.add(desktop);
        fade.to(1,System.nanoTime(),Theme.HOVER_MS);
    }
    public boolean isBindingKey() { return model.capturing(); }

    public void appearance() {
        if (secondary instanceof ThemeFrame) { tree.raise(secondary); return; }
        hideSecondary();
        float pageWidth=Math.min(186,contentWidth());
        secondary = new ThemeFrame(prefs,this::hideSecondary,style.pageLeft(pageWidth),style.pageTop(100),pageWidth);
        desktop.add(secondary);
    }
    private void visibility() {
        if (secondary instanceof ModuleVisibilityFrame) { tree.raise(secondary); return; }
        hideSecondary();
        float pageWidth=Math.min(186,contentWidth()), pageHeight=Math.min(238,contentHeight());
        var frame = new ModuleVisibilityFrame(model,this::hideSecondary,style.pageLeft(pageWidth),style.pageTop(pageHeight),
                pageWidth,pageHeight);
        secondary = frame;
        desktop.add(frame);
        tree.focus(frame.search());
    }
    private void ysmModels() {
        if (secondary instanceof YsmSelectorFrame) { tree.raise(secondary); return; }
        hideSecondary();
        float w=Math.min(186,contentWidth()), h=Math.min(238,contentHeight());
        secondary = new YsmSelectorFrame(this::hideSecondary,style.pageLeft(w),style.pageTop(h),w,h);
        desktop.add(secondary);
    }
    private void profiles() {
        if (secondary instanceof ConfigProfilesFrame) { tree.raise(secondary); return; }
        hideSecondary();
        float w=Math.min(186,contentWidth()), h=Math.min(218,contentHeight());
        secondary = new ConfigProfilesFrame(name -> {
            ConfigProfiles.load(name);
            prefs.reload();
        }, this::hideSecondary, style.pageLeft(w), style.pageTop(h), w, h);
        desktop.add(secondary);
    }
    private void hideSecondary() {
        if (secondary == null) return;
        desktop.remove(secondary);
        secondary = null;
        prefs.saveNumbers();
    }
    @Override protected boolean acceptsTypeAhead() { return true; }
    @Override protected float fontSize() { return Theme.Floating.TEXT_SIZE; }
    @Override protected float contentScale() { return .85f; }
    @Override protected void beforeFrame() {
        if (parameterEpoch != com.blanoir.tempos.client.config.Settings.valuesEpoch()) {
            parameterEpoch = com.blanoir.tempos.client.config.Settings.valuesEpoch();
            prefs.reload();
        }
        desktop.bounds(0,0,contentWidth(),contentHeight());
    }
    @Override protected float opacity(long now) { return fade.value(now); }
    @Override public boolean charTyped(CharacterEvent c) {
        if (suppressBoundCharacter || model.capturing()) { suppressBoundCharacter=false;return true; }
        if (!tree.wantsText() && !tree.hasPopup() && !Character.isISOControl(c.codepoint())) {
            if (secondary instanceof ModuleVisibilityFrame frame) tree.focus(frame.search());
            else if (secondary instanceof ConfigProfilesFrame frame) tree.focus(frame.nameField());
            else if (secondary instanceof YsmSelectorFrame frame) {
                if (!frame.search().visible()) return false;
                tree.focus(frame.search());
            }
            else style.focusSearch();
        }
        return super.charTyped(c);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        suppressBoundCharacter=model.acceptBinding(event);
        if (suppressBoundCharacter) return true;
        if (event.key()==InputConstants.KEY_ESCAPE && !tree.hasPopup()) {
            if (secondary instanceof ModuleVisibilityFrame frame && !frame.search().value().isEmpty()) {
                frame.search().value(""); return true;
            }
            if (secondary instanceof YsmSelectorFrame frame && !frame.search().value().isEmpty()) {
                frame.search().value(""); return true;
            }
            if (secondary != null) { hideSecondary(); return true; }
            if (style.endSearch()) return true;
        }
        boolean handled=super.keyPressed(event);
        prefs.saveNumbers();
        return handled;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        boolean result=super.mouseReleased(event);
        prefs.saveNumbers();
        return result;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        boolean result=super.mouseScrolled(x,y,horizontal,vertical);
        prefs.saveNumbers();
        return result;
    }
    @Override public void onClose() {
        if (!closing) { closing=true; fade.to(0,System.nanoTime(),Theme.HOVER_MS); }
    }
    @Override public void tick() {
        super.tick();
        if (closing && !fade.moving(System.nanoTime())) MinecraftClientAccess.setScreen(Minecraft.getInstance(),null);
    }
    @Override public void removed() {
        super.removed();
        prefs.saveNumbers();
    }
}
