package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;

/** A floating window with a draggable title bar and a clipped content area. */
public class Window extends Widget {
    public static final float HEADER = 25;
    private final String title;
    private String icon;
    private Runnable close;
    private final Widget body;
    private float grabX, grabY, expandedHeight;
    private boolean dragging, moved, folded;

    public Window(String title, Widget body, float x, float y, float width, float height) {
        this.title = title;
        this.body = body;
        expandedHeight = Math.max(headerHeight(), height);
        bounds(x, y, width, expandedHeight);
        add(body);
    }
    public boolean folded() { return folded; }
    protected float headerHeight() { return HEADER; }
    protected boolean canFold() { return true; }
    protected void expandedSize(float height) { expandedHeight = Math.max(headerHeight(), height); }
    public void icon(String id) { icon = id; repaint(); }
    public void onDismiss(Runnable action) { close = action; repaint(); }
    public void folded(boolean value) {
        if (folded == value || value && !canFold()) return;
        folded = value;
        body.visible(!value);
        bounds(x, y, width, value ? headerHeight() : expandedHeight);
    }
    @Override protected void layout() {
        if (!folded) body.bounds(x + 1, y + headerHeight(), width - 2, height - headerHeight() - 1);
    }
    @Override protected void paint(WidgetCanvas c) {
        c.flushText();
        paintFrame(c);
        paintTitle(c);
    }
    protected void paintFrame(WidgetCanvas c) {
        c.cover(x, y, width, height, Theme.WINDOW_RADIUS);
        c.paint.shadow(x, y + 2, width, height, Theme.WINDOW_RADIUS, 5, Theme.SHADOW);
        c.paint.fill(x, y, width, height, Theme.WINDOW_RADIUS, Theme.PANEL);
        c.paint.rounded(x, y, width, HEADER, Theme.WINDOW_RADIUS, Theme.WINDOW_RADIUS,
                folded ? Theme.WINDOW_RADIUS : 0, folded ? Theme.WINDOW_RADIUS : 0, Theme.HEADER, Theme.HEADER);
        c.paint.outline(x, y, width, height, Theme.WINDOW_RADIUS, 1, Theme.BORDER);
        if (!folded) c.paint.fill(x + 1, y + HEADER - 1, width - 2, 1, 0, Theme.BORDER);
    }
    protected void paintTitle(WidgetCanvas c) {
        float middle = y + HEADER / 2;
        if (icon != null) c.icon(icon, x + 7, middle - 6.5f, 13, Theme.MUTED);
        c.centeredLabel(title, x + width / 2, middle, Theme.TEXT);
        paintFold(c, x + width - (close == null ? 11 : 27), middle);
        if (close != null) c.centeredLabel("×", x + width - 10, middle, Theme.MUTED);
    }
    protected final void paintFold(WidgetCanvas c, float cx, float cy) {
        float direction=folded ? 1 : -1;
        c.paint.line(cx-2.5f,cy-direction,cx,cy+direction,.9f,Theme.MUTED);
        c.paint.line(cx,cy+direction,cx+2.5f,cy-direction,.9f,Theme.MUTED);
    }
    @Override protected boolean input(UiEvent e) {
        if (e.kind == UiEvent.Kind.PRESS && e.y < y + headerHeight()) {
            if (e.button == 0 && close != null && e.x >= x + width - 17) { close.run(); return true; }
            if (e.button == UiEvent.PRIMARY && canFold() && e.x >= x + width - (close == null ? 22 : 36)) {
                folded(!folded);
                layoutChanged();
                return true;
            }
            if (e.button != 0) return false;
            dragging = true;
            moved = false;
            grabX = e.x - x;
            grabY = e.y - y;
            return true;
        }
        if (e.kind == UiEvent.Kind.MOVE && dragging) {
            if (!moved && Math.abs(e.x - x - grabX) < 3 && Math.abs(e.y - y - grabY) < 3) return true;
            moved = true;
            float maxX = Math.max(0, tree.root.width - width), maxY = Math.max(0, tree.root.height - headerHeight());
            bounds(Math.clamp(e.x - grabX, 0, maxX), Math.clamp(e.y - grabY, 0, maxY), width, height);
            return true;
        }
        if (e.kind == UiEvent.Kind.RELEASE && dragging) {
            dragging = false;
            if (moved) layoutChanged();
            return true;
        }
        return false;
    }
    @Override protected void captureLost() { if (dragging) { dragging = false; if (moved) layoutChanged(); } }
    protected final boolean dragging() { return dragging && moved; }
    protected void layoutChanged() {}
}
