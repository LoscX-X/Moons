package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;
import java.util.function.DoubleConsumer;

/** A variable-height list. Offscreen rows retain their state and are skipped during painting. */
public class Scroll extends Widget {
    private final Anim offset = new Anim(0);
    private float position, maximum;
    private boolean scrolling, dragging;
    private final boolean symmetricGutters;
    private float grab;
    private static final float LANE = 5;
    public Scroll() { this(true); }
    public Scroll(boolean symmetricGutters) { this.symmetricGutters = symmetricGutters; }
    private DoubleConsumer finished;
    protected float rowGap() { return 0; }
    public float offset() { return position; }
    public float limit() { return maximum; }
    public void onScrollFinished(DoubleConsumer callback) { finished = callback; }
    public void restore(float value) {
        scrolling = false;
        offset.snap(Math.max(0, value));
        position = offset.target();
        relayout();
    }
    void reveal(Widget child) {
        float delta = child.y < y ? child.y - y : Math.max(0, child.y + child.height - y - height);
        if (delta != 0) restore(Math.clamp(position + delta, 0, maximum));
    }

    @Override protected boolean clipChildren() { return true; }
    @Override protected void layout() {
        float total = 0;
        for (int i = 0; i < children.size(); i++) {
            Widget row = children.get(i);
            if (row.visible()) total += row.preferredHeight() + rowGap();
        }
        maximum = Math.max(0, total - rowGap() - height);
        if (offset.target() > maximum) offset.snap(maximum);
        position = Math.min(position, maximum);
        float top = y - position;
        for (int i = 0; i < children.size(); i++) {
            Widget row = children.get(i);
            if (!row.visible()) continue;
            float h = row.preferredHeight();
            // Equal gutters keep the list's type axis fixed when the scrollbar appears.
            float right = maximum > 0 ? LANE : 0;
            float left = symmetricGutters ? right : 0;
            row.bounds(x + left, top, Math.max(0, width - left - right), h);
            top += h + rowGap();
        }
    }
    private float thumbHeight() { return Math.min(height, Math.max(8, height * height / (height + maximum))); }
    private float thumbTop() { return y + (height - thumbHeight()) * position / maximum; }
    @Override Widget hit(float px, float py) {
        if (maximum > 0 && contains(px, py) && px >= x + width - LANE) return this;
        return super.hit(px, py);
    }
    @Override protected void paintForeground(WidgetCanvas c) {
        if (maximum > 0) c.paint.fill(x + width - 2.75f, thumbTop()+1, 1.5f, thumbHeight()-2, .75f,
                dragging || hovered() ? Theme.MUTED : Theme.TRACK);
    }
    private void dragTo(float pointer) {
        float travel = height - thumbHeight();
        if (travel > 0) restore(Math.clamp((pointer - grab - y) / travel, 0, 1) * maximum);
    }
    @Override protected boolean input(UiEvent e) {
        if (dragging) {
            if (e.kind == UiEvent.Kind.MOVE) { dragTo(e.y); return true; }
            if (e.kind == UiEvent.Kind.RELEASE) { dragTo(e.y); captureLost(); return true; }
        }
        if (maximum > 0 && e.kind == UiEvent.Kind.PRESS && e.button == UiEvent.PRIMARY && e.x >= x + width - LANE) {
            float at = e.y - thumbTop();
            grab = at >= 0 && at <= thumbHeight() ? at : thumbHeight() / 2;
            dragging = true;
            dragTo(e.y);
            return true;
        }
        if (e.kind != UiEvent.Kind.SCROLL || maximum <= 0 || e.wheel == 0) return false;
        float next = Math.clamp(offset.target() - e.wheel * 8, 0, maximum);
        if (next == offset.target()) return false;
        offset.to(next, e.time, Theme.HOVER_MS);
        scrolling = true;
        return true;
    }
    @Override protected void captureLost() {
        if (dragging) { dragging = false; scrollFinished(position); repaint(); }
    }
    @Override protected void tick(long now) {
        float next = offset.value(now);
        if (position != next) { position = next; relayout(); }
        if (scrolling && !offset.moving(now)) { scrolling = false; scrollFinished(position); }
    }
    protected void scrollFinished(float value) { if (finished != null) finished.accept(value); }
}
