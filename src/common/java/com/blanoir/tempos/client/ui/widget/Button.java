package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;
import java.util.function.BooleanSupplier;

/** A labelled action or toggle. The caller owns the value and its persistence. */
public class Button extends Widget {
    private final String label;
    private final Runnable action;
    private final BooleanSupplier selected;
    private final Anim fill = new Anim(0);
    private float amount;

    public Button(String label, Runnable action) { this(label, action, null); }
    public Button(String label, Runnable action, BooleanSupplier selected) {
        this.label = label;
        this.action = action;
        this.selected = selected;
    }
    @Override protected boolean focusable() { return true; }
    @Override public float preferredHeight() { return Theme.LINE; }
    @Override protected void tick(long now) {
        fill.to(selected != null && selected.getAsBoolean() ? 1 : 0, now, Theme.ENABLE_MS);
        float next = fill.value(now);
        if (next != amount) { amount = next; repaint(); }
    }
    @Override protected boolean input(UiEvent e) {
        if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER
                || e.kind == UiEvent.Kind.PRESS && e.button == 0) {
            action.run();
            repaint();
            return true;
        }
        return false;
    }
    @Override protected void paint(WidgetCanvas c) {
        c.paint.fill(x, y, width, height, Theme.ROW_RADIUS, hovered() ? Theme.HOVER : Theme.ROW);
        if (amount > 0) {
            c.paint.accentFill(x, y + 2, 2, height - 4, 1, (int)(255 * amount), 1);
        }
        float textX = c.centeredX(label, x + width / 2);
        float textY = c.centeredY(label, y + height / 2);
        if (amount > .5f) c.accentLabel(label, textX, textY, 255, 1);
        else c.label(label, textX, textY, Theme.TEXT);
    }
}
