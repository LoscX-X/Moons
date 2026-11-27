package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.*;
import java.util.ArrayList;

/** Coordinates open categories and temporary search results without changing their saved visibility. */
final class FloatingDesktop extends Widget {
    final GuiModel model;
    final CategoryWindows actions;
    final ArrayList<CategoryFrame> windows = new ArrayList<>();
    private final ArrayList<CategoryFrame> opened = new ArrayList<>();
    final MainWindow main;
    private long seen = -1;
    private String query = "";
    private boolean savePlacement;

    FloatingDesktop(GuiModel model, CategoryWindows actions) {
        this.model = model;
        this.actions = actions;
        String selected = Settings.getString("clickgui.floating.category", "render");
        for (var group : ModuleCategory.ordered()) {
            CategoryFrame frame = new CategoryFrame(model,group,group.name().equalsIgnoreCase(selected));
            windows.add(frame);
            if (frame.open) opened.add(frame);
        }
        main = new MainWindow(this);
        add(main);
        for (CategoryFrame frame : windows) add(frame);
        refresh();
    }
    void select(CategoryFrame frame) {
        model.cancelCapture();
        try (var batch = Settings.batch()) {
            if (!model.emptySearch()) {
                main.endSearch();
                if (!frame.open) { frame.open(true); opened.add(frame); }
            } else {
                frame.open(!frame.open);
                if (frame.open) opened.add(frame); else opened.remove(frame);
            }
            if (frame.open) {
                // Older profiles infer an open window from the last category until its visibility is saved.
                for (var window : opened) Settings.setBoolean(window.prefix + "visible", true);
                Settings.setString("clickgui.floating.category",frame.id);
                tree.raise(frame);
            }
        }
        savePlacement = true;
        refresh();
    }
    private void refresh() {
        seen = model.revision();
        boolean changed = !query.equals(model.query());
        query = model.query();
        for (int i=0; i<windows.size(); i++) {
            var frame = windows.get(i);
            frame.filter(model,changed);
            frame.visible(query.isEmpty() ? frame.open : frame.matches);
            model.watch(frame.category,frame.visible());
        }
        relayout();
    }
    @Override protected void tick(long now) {
        model.update(now);
        if (model.revision()!=seen) refresh();
    }
    float pageLeft(float w) {
        float right=main.left()+main.width(), left=main.left();
        for(int i=0;i<windows.size();i++) {
            var window=windows.get(i);
            if(!window.visible()) continue;
            right=Math.max(right,window.left()+window.width());
            left=Math.min(left,window.left());
        }
        if(right+Floating.GAP+w<=width) return right+Floating.GAP;
        if(left>=Floating.GAP+w) return left-Floating.GAP-w;
        return Math.clamp(main.left()+main.width()+Floating.GAP,0,Math.max(0,width-w));
    }
    @Override protected void layout() {
        if (width <= 0 || height <= 0) return;
        main.place(width,height);
        var order = query.isEmpty() ? opened : windows;
        for (int i = 0; i < order.size(); i++) {
            var frame = order.get(i);
            if (frame.visible() && frame.hasPosition()) frame.place(width, height);
        }
        for (int i = 0; i < order.size(); i++) {
            var frame = order.get(i);
            if (frame.visible() && !frame.hasPosition()) placeNew(frame);
        }
        if (savePlacement) {
            savePlacement = false;
            rememberPositions();
        }
    }
    private void placeNew(CategoryFrame frame) {
        float span = Floating.WIDTH + Floating.GAP;
        float right = main.left() + main.width() + Floating.GAP;
        int rightSlots = (int)Math.max(0,Math.floor((width - right + Floating.GAP) / span));
        int leftSlots = (int)Math.max(0,Math.floor((main.left() + .01f) / span));
        int slots = Math.max(1, rightSlots + leftSlots);
        float px = 0, py = 0;
        for (int slot = 0; slot < slots * (windows.size() + 1); slot++) {
            int column = slot % slots;
            px = column < rightSlots ? right + column * span : main.left() - (column - rightSlots + 1) * span;
            py = main.top() + (slot / slots) * (Floating.HEADER + Floating.GAP);
            if (rightSlots+leftSlots==0) { px=right; py+=Floating.HEADER+Floating.GAP; }
            px = Math.clamp(px, 0, Math.max(0, width - Floating.WIDTH));
            py = Math.clamp(py, 0, Math.max(0, height - Floating.HEADER));
            if (headerIsFree(px, py)) break;
        }
        frame.initialPosition(px, py);
        frame.place(width, height);
    }
    private boolean headerIsFree(float x, float y) {
        for (int i = 0; i < windows.size(); i++) {
            var other = windows.get(i);
            if (other.visible() && other.hasPosition() && Math.abs(other.left() - x) < other.width()
                    && Math.abs(other.top() - y) < Floating.HEADER) return false;
        }
        return true;
    }
    void rememberPositions() {
        try (var batch = Settings.batch()) {
            for (var frame : windows) frame.savePosition();
        }
    }
    void menu(Widget anchor) {
        Scroll choices = new Scroll() {
            @Override protected void paint(WidgetCanvas c) { FloatingWindow.surface(c,x,y,width,height); }
        };
        option(choices,"HUD editor",actions.hud);
        option(choices,"Appearance",actions.appearance);
        option(choices,"Module visibility",actions.visibility);
        if (actions.ysm!=null) option(choices,"YSM models",actions.ysm);
        if (actions.profiles!=null) option(choices,"Profiles",actions.profiles);
        option(choices,"Reset GUI positions",this::resetPositions);
        int rows=4+(actions.ysm==null?0:1)+(actions.profiles==null?0:1);
        float w=Math.min(width,Floating.WIDTH), h=Math.min(height,rows*Floating.ROW);
        float below=anchor.top()+anchor.height()+2;
        float px=Math.clamp(anchor.left()+anchor.width()-w,0,Math.max(0,width-w));
        float py=below+h<=height ? below : Math.max(0,anchor.top()-h-2);
        choices.bounds(px,py,w,h);
        tree.popup(choices);
    }
    private void resetPositions() {
        savePlacement = false;
        try (var batch = Settings.batch()) {
            main.resetPosition();
            for (CategoryFrame frame : windows) frame.resetPosition();
        }
        relayout();
    }
    private void option(Scroll choices,String label,Runnable action) {
        choices.add(new Button(label,() -> { tree.closePopup(); action.run(); }) {
            @Override public float preferredHeight() { return Floating.ROW; }
            @Override protected boolean lightText() { return true; }
            @Override protected void paint(WidgetCanvas c) {
                if (hovered() || focused()) c.paint.fill(x+1,y+1,width-2,height-2,0,Floating.HOVER);
                c.label(label,c.leftX(label,x+7),c.centeredY(label,y+height/2),Floating.MODULE);
            }
        });
    }
}
