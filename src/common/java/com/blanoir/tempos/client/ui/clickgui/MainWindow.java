package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.config.ClientBranding;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.*;

final class MainWindow extends Window {
    private final FloatingDesktop desktop;
    final Scroll list;
    final Button gear, find;
    private final Button editHud, manage;
    final TextField search;
    private final String brand = ClientBranding.name();
    private float homeX, homeY;
    private boolean editing;
    private static final String PREFIX = "clickgui.floating.main.";

    MainWindow(FloatingDesktop desktop) { this(desktop, new Scroll(false)); }
    private MainWindow(FloatingDesktop desktop, Scroll list) {
        super("", list, Floating.MARGIN, Floating.TOP, Floating.WIDTH, 220);
        this.desktop = desktop;
        this.list = list;
        homeX = (float)Settings.getDouble(PREFIX + "x", Floating.MARGIN);
        homeY = (float)Settings.getDouble(PREFIX + "y", Floating.TOP);
        for (var frame : desktop.windows)
            list.add(new Entry(frame.category.label(),frame.id,() -> desktop.select(frame),frame));
        list.add(new Widget() {
            @Override public float preferredHeight() { return Floating.GROUP; }
            @Override protected float textSize() { return Floating.DETAIL_SIZE; }
            @Override protected void paint(WidgetCanvas c) {
                c.label("MISC", c.leftX("MISC",x+6), c.centeredY("MISC", y+height/2), Floating.GROUP_TITLE);
                c.paint.fill(x+35,y+height/2,width-42,1/c.paint.scale(),0,Floating.RULE);
            }
        });
        list.add(new Entry("HUD editor","render",desktop.actions.hud,null));
        list.add(new Entry("Appearance","settings",desktop.actions.appearance,null));
        list.add(new Entry("Module visibility","all_modules",desktop.actions.visibility,null));
        gear = iconButton("Settings","settings",this::menu);
        find = iconButton("Search modules","search",this::beginSearch);
        editHud = iconButton("HUD editor","player",desktop.actions.hud);
        manage = iconButton("Module visibility","all_modules",desktop.actions.visibility);
        search = new TextField("Search modules", desktop.model::search, true) {
            @Override protected void focusChanged(boolean on) {
                super.focusChanged(on);
                if (!on && editing) { editing = false; visible(false); relayout(); }
            }
        };
        search.visible(false);
        add(gear); add(editHud); add(manage); add(find); add(search);
    }
    private Button iconButton(String label, String icon, Runnable action) {
        Button button = new Button(label,action) {
            @Override protected void paint(WidgetCanvas c) {
                c.icon(icon,x+(width-Floating.ICON)/2,y+(height-Floating.ICON)/2,Floating.ICON,hovered()?Floating.LABEL_HI:Floating.GEAR);
            }
        };
        button.tooltip(label);
        return button;
    }
    private void menu() { desktop.menu(gear); }
    void resetPosition() {
        homeX = Floating.MARGIN;
        homeY = Floating.TOP;
        Settings.remove(PREFIX + "x");
        Settings.remove(PREFIX + "y");
    }
    void beginSearch() {
        editing = true;
        search.visible(true);
        tree.raise(this);
        tree.focus(search);
        relayout();
    }
    boolean endSearch() {
        boolean used = !search.value().isEmpty();
        if (used || editing) {
            search.value("");
            editing = false;
            search.visible(false);
            relayout();
        }
        return used;
    }
    private final class Entry extends Button {
        final String title, icon;
        final CategoryFrame frame;
        final Anim hover = new Anim(0);
        float brightness;
        Entry(String title,String icon,Runnable action,CategoryFrame frame) {
            super(title,action);
            this.title = title; this.icon = icon; this.frame = frame;
        }
        @Override public float preferredHeight() { return Floating.ROW; }
        @Override protected boolean lightText() { return true; }
        @Override protected void tick(long now) {
            hover.to(hovered() || frame != null && frame.visible() ? 1 : 0,now,Theme.HOVER_MS);
            float next = hover.value(now);
            if (brightness != next) { brightness = next; repaint(); }
        }
        @Override protected void paint(WidgetCanvas c) {
            float mid = y+height/2;
            if (brightness>0) c.paint.fill(x,y,width,height,0,(Math.round(255*brightness)<<24) | Floating.HOVER & 0xffffff);
            boolean selected = frame != null && frame.visible();
            float ix=x+Floating.ICON_X-1, iy=mid-Floating.ICON/2;
            if (selected) c.accentIcon(icon,ix,iy,Floating.ICON);
            else c.icon(icon,ix,iy,Floating.ICON,Floating.ICON_COLOR);
            float tx=c.leftX(title,x+Floating.TEXT_X-1), ty=c.centeredY(title,mid);
            if (selected) c.accentLabel(title,tx,ty,255,1); else c.label(title,tx,ty,Floating.LABEL);
            float right=x+width-Floating.ARROW_RIGHT;
            c.paint.line(right-1.6f,mid-1.8f,right,mid,.85f,Floating.ARROW);
            c.paint.line(right,mid,right-1.6f,mid+1.8f,.85f,Floating.ARROW);
        }
    }
    void place(float screenWidth,float screenHeight) {
        float w=Math.min(screenWidth,Floating.WIDTH);
        float content=Floating.HEADER+desktop.windows.size()*Floating.ROW+Floating.GROUP+3*Floating.ROW+Floating.FOOTER;
        float h=Math.min(content,screenHeight);
        float px=Math.clamp(dragging()?x:homeX,0,Math.max(0,screenWidth-w));
        float py=Math.clamp(dragging()?y:homeY,0,Math.max(0,screenHeight-(Floating.HEADER+Floating.FOOTER)));
        bounds(px,py,w,Math.min(h,screenHeight-py));
    }
    @Override protected boolean canFold() { return false; }
    @Override protected float headerHeight() { return Floating.HEADER; }
    @Override protected void layout() {
        list.bounds(x+1,y+Floating.HEADER,width-2,Math.max(0,height-Floating.HEADER-Floating.FOOTER));
        gear.bounds(x+width-19,y,19,Floating.HEADER);
        float footer=y+height-Floating.FOOTER;
        editHud.visible(!editing); manage.visible(!editing);
        editHud.bounds(x+1,footer,20,Floating.FOOTER);
        manage.bounds(x+width-39,footer,18,Floating.FOOTER);
        find.bounds(x+width-21,footer,20,Floating.FOOTER);
        search.bounds(x+2,footer+1,width-24,Floating.FOOTER-2);
    }
    @Override protected void paintFrame(WidgetCanvas c) {
        FloatingWindow.surface(c,x,y,width,height);
        c.paint.rounded(x+1/c.paint.scale(),y+1/c.paint.scale(),width-2/c.paint.scale(),Floating.HEADER-1/c.paint.scale(),
                Floating.RADIUS,Floating.RADIUS,0,0,Floating.TITLE_BAR,Floating.TITLE_BAR);
        c.paint.fill(x+1,y+height-Floating.FOOTER,width-2,1/c.paint.scale(),0,Floating.RULE);
    }
    @Override protected void paintTitle(WidgetCanvas c) {
        c.paint.pushClip(x+7,y,width-29,Floating.HEADER);
        var previous=c.useFont(Floating.BRAND_SIZE,false);
        c.label(brand,c.leftX(brand,x+7),c.centeredY(brand,y+Floating.HEADER/2),Floating.BRAND);
        c.restoreFont(previous);
        c.paint.popClip();
    }
    @Override protected void layoutChanged() {
        homeX=x; homeY=y;
        try (var batch=Settings.batch()) {
            Settings.setDouble(PREFIX+"x",x);
            Settings.setDouble(PREFIX+"y",y);
            desktop.rememberPositions();
        }
        relayout();
    }
}
