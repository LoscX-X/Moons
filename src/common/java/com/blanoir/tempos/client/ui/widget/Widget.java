package com.blanoir.tempos.client.ui.widget;

import java.util.ArrayList;

/** Retained bounds are expressed in screen GUI coordinates, also used for hit testing and clipping. */
public class Widget {
    protected float x, y, width, height;
    protected Widget parent;
    protected WidgetTree tree;
    protected final ArrayList<Widget> children = new ArrayList<>();
    private boolean visible = true;
    private String tooltip = "";

    public final void add(Widget child) {
        if (child == this || child.parent != null || child.tree != null) throw new IllegalArgumentException("Widget already attached");
        for (Widget p = parent; p != null; p = p.parent) if (p == child) throw new IllegalArgumentException("Widget cycle");
        children.add(child);
        child.parent = this;
        child.attach(tree);
        relayout();
    }

    public final void remove(Widget child) {
        if (!children.remove(child)) return;
        if (tree != null) tree.detach(child);
        child.parent = null;
        child.attach(null);
        relayout();
    }

    final void attach(WidgetTree tree) {
        this.tree = tree;
        for (int i = 0; i < children.size(); i++) children.get(i).attach(tree);
    }

    public final void bounds(float x, float y, float w, float h) {
        w = Math.max(0, w);
        h = Math.max(0, h);
        if (x == this.x && y == this.y && w == width && h == height) return;
        this.x = x;
        this.y = y;
        width = w;
        height = h;
        relayout();
    }

    public final void visible(boolean next) {
        if (visible == next) return;
        visible = next;
        if (!visible && tree != null) tree.detach(this);
        relayout();
    }
    public final boolean visible() { return visible; }
    public final float left() { return x; }
    public final float top() { return y; }
    public final float width() { return width; }
    public final float height() { return height; }
    public final void tooltip(String text) { tooltip = text == null ? "" : text; }
    public String tooltip() { return tooltip; }
    protected final void repaint() { if (tree != null) tree.repaint(); }
    protected final void relayout() { if (tree != null) tree.relayout(); }
    protected final boolean hovered() { return tree != null && tree.hovered() == this; }
    protected final boolean focused() { return tree != null && tree.focused() == this; }
    public float preferredHeight() { return height; }
    protected boolean focusable() { return false; }
    protected boolean textInput() { return false; }
    protected boolean clipChildren() { return false; }
    protected boolean lightText() { return false; }
    protected float textSize() { return 0; }
    protected void layout() {}
    protected void tick(long now) {}
    protected void paint(WidgetCanvas canvas) {}
    protected void paintForeground(WidgetCanvas canvas) {}
    protected boolean input(UiEvent event) { return false; }
    protected void focusChanged(boolean focused) {}
    protected void captureLost() {}
    protected boolean contains(float px, float py) {
        return visible && px >= x && py >= y && px < x + width && py < y + height;
    }

    Widget hit(float px, float py) {
        if (!contains(px, py)) return null;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget hit = children.get(i).hit(px, py);
            if (hit != null) return hit;
        }
        return this;
    }

    final void layoutTree() {
        if (!visible) return;
        layout();
        for (int i = 0; i < children.size(); i++) children.get(i).layoutTree();
    }
    final void tickTree(long now) {
        if (!visible) return;
        tick(now);
        for (int i = 0; i < children.size(); i++) children.get(i).tickTree(now);
    }
    final void paintTree(WidgetCanvas c) {
        if (!visible || width <= 0 || height <= 0) return;
        float scale = c.paint.scale();
        if ((x + width) * scale <= c.paint.clipLeft() || x * scale >= c.paint.clipRight()
                || (y + height) * scale <= c.paint.clipTop() || y * scale >= c.paint.clipBottom()) return;
        var previous = c.useFont(textSize(), lightText());
        paint(c);
        boolean clip = clipChildren();
        if (clip) c.paint.pushClip(x, y, width, height);
        for (int i = 0; i < children.size(); i++) children.get(i).paintTree(c);
        paintForeground(c);
        if (clip) c.paint.popClip();
        c.restoreFont(previous);
    }
}
