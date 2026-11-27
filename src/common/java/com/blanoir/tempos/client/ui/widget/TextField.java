package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;
import java.util.Objects;
import java.util.function.Consumer;

/** Single-line Unicode editor. Cursor and selection always lie on code point boundaries. */
public class TextField extends Widget {
    private final String placeholder;
    private final Consumer<String> changed;
    private final boolean clearOnSecondary;
    private String value = "";
    private int cursor, anchor;
    private float offset;
    private float[] advances = new float[1];
    private boolean metricsDirty = true, selecting, caret;
    private boolean settingStyle;
    private WidgetCanvas measuredWith;
    private float bearing;
    private long blinkStart;

    public TextField(String placeholder, Consumer<String> changed) {
        this(placeholder, changed, false);
    }
    public TextField(String placeholder, Consumer<String> changed, boolean clearOnSecondary) {
        this.placeholder = placeholder;
        this.changed = Objects.requireNonNull(changed);
        this.clearOnSecondary = clearOnSecondary;
    }
    public String value() { return value; }
    public void value(String next) {
        if (next.equals(value)) return;
        value = next;
        cursor = anchor = next.length();
        metricsDirty = true;
        changed.accept(next);
        repaint();
    }
    public void selectAll() { anchor = 0; step(value.length(), true); }
    void useSettingStyle() { settingStyle = true; metricsDirty = true; }
    private float inset() { return settingStyle ? 5 : Theme.PAD; }
    @Override public float preferredHeight() { return settingStyle ? 14 : Theme.LINE + 3; }
    @Override protected float textSize() { return settingStyle ? Theme.Floating.TEXT_SIZE : Theme.Floating.FIELD_SIZE; }
    @Override protected boolean focusable() { return true; }
    @Override protected boolean textInput() { return true; }
    @Override protected void focusChanged(boolean on) { selecting = false; resetBlink(); }
    @Override protected void captureLost() { selecting = false; }
    @Override protected void tick(long now) {
        if (!focused()) return;
        boolean next = ((now - blinkStart) / 500_000_000L & 1) == 0;
        if (next != caret) { caret = next; repaint(); }
    }
    private void resetBlink() { blinkStart = System.nanoTime(); caret = true; repaint(); }

    private void measure(WidgetCanvas c) {
        if (!metricsDirty && measuredWith == c) return;
        measuredWith = c;
        String display = value.isEmpty() ? placeholder : value;
        bearing = !display.isEmpty() && !Character.isWhitespace(display.codePointAt(0)) ? c.leftX(display,0) : 0;
        if (advances.length <= value.length()) advances = new float[Math.max(16, 2 * value.length() + 1)];
        boolean nativeRun = c.usesNative(value);
        for (int i = 0; i < value.length();) {
            int end = value.offsetByCodePoints(i, 1);
            String prefix = value.substring(0, end);
            advances[end] = nativeRun ? c.nativeWidth(prefix) : c.width(prefix);
            i = end;
        }
        metricsDirty = false;
    }
    private int locate(float px) {
        if (metricsDirty) return cursor;
        float target = px - x - inset() + offset - bearing;
        for (int i = 0; i < value.length();) {
            int end = value.offsetByCodePoints(i, 1);
            if (target < (advances[i] + advances[end]) * .5f) return i;
            i = end;
        }
        return value.length();
    }
    private void replace(String replacement) {
        int lo = Math.min(anchor, cursor), hi = Math.max(anchor, cursor);
        String next = value.substring(0, lo) + replacement + value.substring(hi);
        if (!next.equals(value)) {
            value = next;
            metricsDirty = true;
            changed.accept(next);
        }
        cursor = anchor = lo + replacement.length();
        resetBlink();
    }
    private void step(int next, boolean extend) {
        cursor = next;
        if (!extend) anchor = cursor;
        resetBlink();
    }
    @Override protected boolean input(UiEvent e) {
        if (clearOnSecondary && e.kind == UiEvent.Kind.PRESS && e.button == UiEvent.SECONDARY) { value(""); return true; }
        if (e.kind == UiEvent.Kind.PRESS && e.button == 0) {
            if (e.doubleClick) { anchor = 0; cursor = value.length(); }
            else step(locate(e.x), e.shift);
            selecting = true;
            resetBlink();
            return true;
        }
        if (e.kind == UiEvent.Kind.MOVE && selecting) { step(locate(e.x), true); return true; }
        if (e.kind == UiEvent.Kind.RELEASE && selecting) { selecting = false; return true; }
        if (e.kind == UiEvent.Kind.CHARACTER) {
            if (!Character.isValidCodePoint(e.codepoint) || Character.isISOControl(e.codepoint)
                    || e.codepoint >= 0xd800 && e.codepoint <= 0xdfff) return false;
            replace(Character.toString(e.codepoint));
            return true;
        }
        if (e.kind != UiEvent.Kind.KEY) return false;
        int lo = Math.min(anchor, cursor), hi = Math.max(anchor, cursor);
        switch (e.key) {
            case LEFT -> step(!e.shift && lo != hi ? lo : cursor > 0 ? value.offsetByCodePoints(cursor, -1) : 0, e.shift);
            case RIGHT -> step(!e.shift && lo != hi ? hi : cursor < value.length() ? value.offsetByCodePoints(cursor, 1) : cursor, e.shift);
            case HOME -> step(0, e.shift);
            case END -> step(value.length(), e.shift);
            case SELECT_ALL -> selectAll();
            case COPY, CUT -> {
                if (lo != hi) {
                    tree.clipboard.set(value.substring(lo, hi));
                    if (e.key == UiEvent.Key.CUT) replace("");
                }
            }
            case PASTE -> {
                String text = tree.clipboard.get();
                if (text != null) replace(text.replace("\r", "").replace("\n", ""));
            }
            case BACKSPACE, DELETE -> {
                if (lo == hi && e.key == UiEvent.Key.BACKSPACE && lo > 0) anchor = value.offsetByCodePoints(lo, -1);
                if (lo == hi && e.key == UiEvent.Key.DELETE && hi < value.length()) anchor = value.offsetByCodePoints(hi, 1);
                if (anchor != cursor) replace("");
            }
            default -> { return false; }
        }
        return true;
    }
    @Override protected void paint(WidgetCanvas c) {
        measure(c);
        float radius = settingStyle ? 0 : Theme.ROW_RADIUS;
        float stroke = settingStyle ? .5f : 1;
        float pad = inset();
        c.cover(x, y, width, height, radius);
        float inner = Math.max(1, width - pad * 2);
        offset = Math.clamp(offset, Math.max(0, advances[cursor] - inner + 1), Math.max(0, advances[cursor]));
        float left = x + pad - offset + bearing;
        float lineHeight = c.usesNative(value) ? c.nativeLineHeight() : c.fontSize();
        float top = y + (height - lineHeight) * .5f;
        c.paint.fill(x, y, width, height, radius, Theme.FIELD);
        if (focused()) c.paint.accentOutline(x, y, width, height, radius, stroke, 255, 1);
        else c.paint.outline(x, y, width, height, radius, stroke, Theme.BORDER);
        c.paint.pushClip(x + pad, y, inner, height);
        if (focused() && cursor != anchor) {
            c.paint.fill(left + Math.min(advances[anchor], advances[cursor]), top - 1,
                    Math.abs(advances[anchor] - advances[cursor]), lineHeight + 2, 0, Theme.SELECTION);
        }
        String text = value.isEmpty() ? placeholder : value;
        c.label(text, left, c.centeredY(text,y + height / 2), value.isEmpty() ? Theme.DIM : Theme.TEXT);
        if (focused() && caret) c.paint.fill(left + advances[cursor], top, 1, lineHeight, 0, Theme.TEXT);
        c.paint.popClip();
    }
}
