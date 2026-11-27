package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.paint.UiFont;
import com.blanoir.tempos.client.ui.paint.UiPainter;
import com.blanoir.tempos.client.ui.theme.Theme;
import net.minecraft.world.item.ItemStack;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Groups the glyphs above a window's surfaces. Missing glyphs route the entire label to the host. */
public final class WidgetCanvas {
    public interface NativeText {
        int width(String text);
        default int lineHeight() { return 9; }
        void label(String text, float x, float y, int color, boolean accent, int left, int top, int right, int bottom, int width);
        default void scaledLabel(String text, float x, float y, int color, boolean accent, int left, int top, int right, int bottom, int width, float scale) {
            label(text,x,y,color,accent,left,top,right,bottom,width);
        }
        void cover(int left, int top, int right, int bottom);
        default void item(ItemStack stack, float x, float y, float size, int left, int top, int right, int bottom) {}
    }

    public final UiPainter paint;
    public final Theme theme;
    private final UiFont font;
    private UiFont face;
    private final NativeText nativeText;
    private final Map<String, Integer> nativeWidths = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> entry) { return size() > 512; }
    };
    private Label[] labels = new Label[64];
    private int size;

    private static final class Label {
        String text;
        float x, y, iconSize;
        UiFont face;
        int color, left, top, right, bottom;
        boolean accent;
        boolean sameClip(Label other) {
            return left == other.left && top == other.top && right == other.right && bottom == other.bottom;
        }
    }

    public WidgetCanvas(UiPainter paint, Theme theme, UiFont font, NativeText nativeText) {
        this.paint = paint;
        this.theme = theme;
        this.font = font;
        this.face = font;
        this.nativeText = nativeText;
    }

    public float width(String text) {
        float width = face.width(text);
        if (!Float.isNaN(width)) return width;
        return nativeWidth(text);
    }
    public void item(ItemStack stack, float x, float y, float size) {
        if (!stack.isEmpty()) nativeText.item(stack,x,y,size,paint.clipLeft(),paint.clipTop(),paint.clipRight(),paint.clipBottom());
    }
    public UiFont useFont(float size, boolean light) {
        UiFont previous = face;
        face = (light ? face.light() : face).atSize(size);
        return previous;
    }
    public void restoreFont(UiFont previous) { face = previous; }
    public float fontSize() { return face.size(); }

    public boolean usesNative(String text) { return Float.isNaN(face.width(text)); }

    /** Anchor visible ink to the same physical pixel grid as its container and neighboring icons. */
    public float centeredX(String text, float center) {
        float ink = face.inkCenterX(text);
        return snap(center - (Float.isNaN(ink) ? nativeWidth(text) / 2 : ink));
    }
    public float leftX(String text, float edge) {
        float ink = face.inkLeft(text);
        return snap(edge - (Float.isNaN(ink) ? 0 : ink));
    }
    public float rightX(String text, float edge) {
        float ink = face.inkRight(text);
        return snap(edge - (Float.isNaN(ink) ? nativeWidth(text) : ink));
    }
    public float centeredY(String text, float center) {
        float ink = face.inkCenterY(text);
        return snap(center - (Float.isNaN(ink) ? nativeLineHeight() / 2f : ink));
    }
    private float snap(float position) {
        return Math.round(position * paint.scale()) / paint.scale();
    }
    public void centeredLabel(String text, float centerX, float centerY, int color) {
        label(text, centeredX(text, centerX), centeredY(text, centerY), color);
    }

    /** Later opaque windows must cover native glyphs queued by earlier windows, too. */
    public void cover(float x, float y, float w, float h, float radius) {
        int left = (int)Math.ceil(x), top = (int)Math.ceil(y);
        int right = (int)Math.floor(x + w), bottom = (int)Math.floor(y + h);
        int r = Math.max(0, Math.min(Math.round(radius), Math.min(right - left, bottom - top) / 2));
        nativeText.cover(left, top + r, right, bottom - r);
        for (int row = 0; row < r; row++) {
            double dy = r - row - .5;
            int inset = (int)Math.ceil(r - Math.sqrt(r * r - dy * dy) - .5);
            nativeText.cover(left + inset, top + row, right - inset, top + row + 1);
            nativeText.cover(left + inset, bottom - row - 1, right - inset, bottom - row);
        }
    }

    public float nativeWidth(String text) {
        return nativeAdvance(text) * (face.size() / Theme.FONT_SIZE);
    }
    public float nativeLineHeight() { return nativeText.lineHeight() * (face.size() / Theme.FONT_SIZE); }
    private int nativeAdvance(String text) {
        Integer found = nativeWidths.get(text);
        if (found != null) return found;
        int measured = nativeText.width(text);
        nativeWidths.put(text, measured);
        return measured;
    }

    public void label(String text, float x, float y, int color) {
        label(text, x, y, color, false, 0);
    }
    public void accentLabel(String text, float x, float y, int alpha, float brightness) {
        label(text, x, y, theme.accentTint(y, alpha, brightness), theme.rgb(), 0);
    }
    public void icon(String id, float x, float y, float size, int color) {
        label(id, x, y, color, false, size);
    }
    public void accentIcon(String id, float x, float y, float size) {
        label(id, x, y, theme.accentTint(y, 255, 1), theme.rgb(), size);
    }
    private void label(String text, float x, float y, int color, boolean accent, float iconSize) {
        if (text.isEmpty() || paint.clipLeft() >= paint.clipRight() || paint.clipTop() >= paint.clipBottom()) return;
        if (size == labels.length) labels = Arrays.copyOf(labels, size * 2);
        Label label = labels[size];
        if (label == null) labels[size] = label = new Label();
        size++;
        label.text = text;
        label.face = face;
        label.x = x;
        label.y = y;
        label.color = color;
        label.accent = accent;
        label.iconSize = iconSize;
        label.left = paint.clipLeft();
        label.top = paint.clipTop();
        label.right = paint.clipRight();
        label.bottom = paint.clipBottom();
    }

    /** Called outside the child clip and before painting anything that should obscure this group. */
    public void flushText() {
        Label previous = null;
        float scale = paint.scale();
        for (int i = 0; i < size; i++) {
            Label label = labels[i];
            if (previous == null || !label.sameClip(previous)) {
                if (previous != null) paint.popClip();
                paint.pushClip(label.left / scale, label.top / scale,
                        (label.right - label.left) / scale, (label.bottom - label.top) / scale);
            }
            if (label.iconSize > 0) font.drawIcon(paint,label.text,label.x,label.y,label.iconSize,label.color,label.accent);
            else if (!label.face.draw(paint, label.text, label.x, label.y, label.color, label.accent)) {
                nativeText.scaledLabel(label.text, label.x, label.y, label.color, label.accent, label.left, label.top, label.right,
                        label.bottom, nativeAdvance(label.text), label.face.size() / Theme.FONT_SIZE);
            }
            label.text = null;
            previous = label;
        }
        if (previous != null) paint.popClip();
        size = 0;
    }
}
