package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.utils.render.ArgbColors;
import com.blanoir.tempos.client.utils.render.ColorCodec;
import java.awt.Color;
import java.util.HexFormat;

/** One color row. Dragging previews an integer color; releasing commits its existing hex setting. */
public final class ColorPicker extends Widget {
    public interface Binding {
        String hex();
        void preview(int color);
        void commit(String hex);
        default boolean rgb() { return false; }
        default void rgb(boolean enabled) {}
    }
    private static final String[] CHANNELS = {"Hue", "Saturation", "Brightness", "Opacity"};
    private static final String[] NUMBERS = new String[256];
    static {
        for (int i = 0; i < NUMBERS.length; i++) NUMBERS[i] = Integer.toString(i);
    }
    private static final float ROW = 25;
    private final Binding binding;
    private final SettingLabel caption;
    private final boolean alpha, accent;
    private final float[] hsba = new float[4];
    private final TextField hex;
    private int channel, color;
    private float pressX;
    private boolean expanded, dragging, invalid, changed;
    private String stored;
    private final Anim hover = new Anim(0);
    private float handleRadius = 3.5f, lastClickX, lastClickY;
    private int lastPart = -1;
    private long lastClick;

    public ColorPicker(Binding binding, boolean alpha, boolean accent) {
        this("", binding, alpha, accent);
    }
    public ColorPicker(String name, Binding binding, boolean alpha, boolean accent) {
        caption = new SettingLabel(name);
        this.binding = binding;
        this.alpha = alpha;
        this.accent = accent;
        hex = new TextField(alpha ? "#aarrggbb" : "#rrggbb", ignored -> {}) {
            @Override protected boolean input(UiEvent e) {
                if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER) { applyHex(); return true; }
                boolean handled = super.input(e);
                if (handled && invalid) { invalid = false; repaint(); }
                return handled;
            }
            @Override protected void focusChanged(boolean on) {
                super.focusChanged(on);
                if (!on) applyHex();
            }
            @Override protected void paint(WidgetCanvas c) {
                super.paint(c);
                if (invalid) c.paint.outline(x, y, width, height, 0, 1 / c.paint.scale(), Theme.DANGER);
            }
        };
        hex.useSettingStyle();
        add(hex);
        hex.visible(false);
        sync();
    }
    /** Called when a profile changes. Reading a setting never rewrites its original string. */
    public void sync() {
        String next = binding.hex();
        if (next == null || next.equals(stored)) return;
        stored = next;
        if (valid(next)) decode(ColorCodec.parseRgbOrArgb(next.substring(1)));
        else decode(0xff000000);
        invalid = !valid(next);
        hex.value(next);
        repaint();
    }
    private boolean valid(String value) {
        if (value.isEmpty() || value.charAt(0) != '#'
                || (value.length() != 7 && (!alpha || value.length() != 9))) return false;
        for (int i = 1; i < value.length(); i++) if (!HexFormat.isHexDigit(value.charAt(i))) return false;
        return true;
    }
    private void decode(int argb) {
        color = argb;
        float previousHue = hsba[0];
        Color.RGBtoHSB(argb >>> 16 & 255, argb >>> 8 & 255, argb & 255, hsba);
        if (hsba[1] == 0) hsba[0] = previousHue;
        hsba[3] = (argb >>> 24) / 255f;
    }
    private void applyHex() {
        String value = hex.value().trim();
        invalid = !valid(value);
        if (!invalid && !value.equals(stored)) {
            if (accent && binding.rgb()) binding.rgb(false);
            decode(ColorCodec.parseRgbOrArgb(value.substring(1)));
            binding.preview(color);
            stored = value;
            binding.commit(value);
        }
        repaint();
    }
    private void component(float value) {
        float next = Math.clamp(value, 0, 1);
        boolean stopRgb = accent && channel == 0 && binding.rgb();
        if (!stopRgb && hsba[channel] == next) return;
        if (stopRgb) binding.rgb(false);
        hsba[channel] = next;
        int rgb = ArgbColors.hsvToOpaqueRgb(hsba[0], hsba[1], hsba[2]);
        color = Math.round(hsba[3] * 255) << 24 | rgb & 0xffffff;
        binding.preview(color);
        changed = true;
        repaint();
    }
    private void commit() {
        if (!changed) return;
        changed = false;
        String digits = HexFormat.of().toHexDigits(color);
        stored = "#" + (alpha && (color >>> 24 != 255 || stored != null && stored.length() == 9) ? digits : digits.substring(2));
        hex.value(stored);
        invalid = false;
        binding.commit(stored);
    }
    private float channelHeight() { return ROW + caption.extraHeight(); }
    private float trackY() { return y + channelHeight() - 6; }
    @Override public float preferredHeight() { return channelHeight() + (expanded ? hex.preferredHeight() + 2 : 0); }
    @Override protected boolean lightText() { return true; }
    @Override protected float textSize() { return Theme.Floating.DETAIL_SIZE; }
    @Override protected void layout() { if (expanded) hex.bounds(x + 5, y + channelHeight(), width - 10, hex.preferredHeight()); }
    private float trackLeft() { return x + 19; }
    private float trackRight() { return Math.max(trackLeft() + 1, x + width - 17); }
    private float handleX() { return trackLeft()+hsba[channel]*(trackRight()-trackLeft()); }
    @Override protected void tick(long now) {
        float dx=tree.pointerX()-handleX(), dy=tree.pointerY()-trackY();
        hover.to(dragging || hovered() && dx*dx+dy*dy<=20 ? 1 : 0,now,150);
        float next=3.5f+.5f*hover.value(now);
        if(next!=handleRadius){handleRadius=next;repaint();}
    }

    @Override protected boolean input(UiEvent e) {
        if (e.kind == UiEvent.Kind.MOVE && dragging) {
            if (e.x != pressX || changed) component((e.x - trackLeft()) / (trackRight() - trackLeft()));
            return true;
        }
        if (e.kind == UiEvent.Kind.RELEASE && dragging) { dragging = false; commit(); return true; }
        if (e.y < y || e.y >= y + channelHeight()) return false;
        boolean lower = e.y >= trackY()-6;
        boolean number = !lower && e.x >= x+width-20;
        boolean track = lower && e.x >= trackLeft()-2 && e.x <= trackRight()+2;
        if (track && e.kind == UiEvent.Kind.SCROLL) {
            component(hsba[channel] + e.wheel * (e.shift ? .25f : 1) / 255);
            commit();
            return true;
        }
        if (e.kind != UiEvent.Kind.PRESS || e.button != UiEvent.PRIMARY) return false;
        int part=lower && e.x >= x+width-12 ? 3 : number ? 2 : track ? 1 : lower && e.x < trackLeft()-2 ? 0 : -1;
        if (part < 0) return false;
        boolean twice = part>=0 && part==lastPart && e.time-lastClick<300_000_000L
                && Math.abs(e.x-lastClickX)<4 && Math.abs(e.y-lastClickY)<4;
        lastPart=twice ? -1 : part;
        lastClick=e.time;lastClickX=e.x;lastClickY=e.y;
        if (part==3) {
            expanded = !expanded;
            hex.visible(expanded);
            relayout();
        } else if (number) {
            if (twice) editNumber();
        } else if (twice) {
            if (accent) { binding.rgb(!binding.rgb()); repaint(); }
        } else if (track) {
            if (e.shift) { channel = (channel + 1) % (alpha ? 4 : 3); repaint(); }
            else { dragging = true; pressX = e.x; }
        }
        return true;
    }
    @Override protected void captureLost() { dragging = false; commit(); }
    private void editNumber() {
        WidgetTree owner = tree;
        TextField number = new TextField(CHANNELS[channel], ignored -> {}) {
            @Override protected boolean input(UiEvent e) {
                if (e.kind != UiEvent.Kind.KEY || e.key != UiEvent.Key.ENTER) return super.input(e);
                try {
                    int value = Integer.parseInt(value().trim());
                    if (value < 0 || value > 255) return true;
                    component(value / 255f);
                    commit();
                    owner.closePopup();
                } catch (NumberFormatException ignored) {
                    // Keep the field open to correct the input.
                }
                return true;
            }
        };
        number.useSettingStyle();
        number.value(NUMBERS[Math.round(255 * hsba[channel])]);
        number.bounds(x+width-42, y, 37, 14);
        owner.childPopup(number);
        owner.focus(number);
        number.selectAll();
    }
    private int endColor(boolean high) {
        double end = high ? 1 : 0;
        return switch (channel) {
            case 1 -> ArgbColors.hsvToOpaqueRgb(hsba[0], end, hsba[2]);
            case 2 -> ArgbColors.hsvToOpaqueRgb(hsba[0], hsba[1], end);
            default -> (high ? 0xff000000 : 0) | color & 0xffffff;
        };
    }
    private static void checker(WidgetCanvas c, float x, float y, float w, float h) {
        c.paint.fill(x, y, w, h, 1, 0xff6c6c72);
        for (int row = 0; row * 4 < h; row++)
            for (int col = row % 2; col * 4 < w; col += 2)
                c.paint.fill(x + col * 4, y + row * 4, Math.min(4, w - col * 4), Math.min(4, h - row * 4), 0, 0xffaaaab0);
    }
    @Override protected void paint(WidgetCanvas c) {
        float numberRight = x+width-5;
        String channelName = CHANNELS[channel];
        float channelRight = numberRight - c.width("255") - 5;
        float channelX = c.rightX(channelName,channelRight);
        if (caption.fit(c, channelX-x-9)) relayout();
        float mid = y + 7.5f + caption.extraHeight()/2;
        caption.draw(c,x+5,mid,Theme.Floating.MODULE);
        c.label(channelName,channelX,c.centeredY(channelName,mid),Theme.MUTED);
        String num=NUMBERS[Math.round(hsba[channel]*255)];
        c.label(num,c.rightX(num,numberRight),c.centeredY(num,mid),Theme.TEXT);
        float left = trackLeft(), right = trackRight(), trackWidth = right - left;
        float cy = trackY(), top = cy-1.5f;
        checker(c, x+5, cy-3.5f, 7, 7);
        if (accent && binding.rgb()) c.paint.accentFill(x+5,cy-3.5f,7,7,1,255,1);
        else c.paint.fill(x+5,cy-3.5f,7,7,1,color);
        float thumb=handleX(), radius=handleRadius;
        // Limit both halves of the ramp to leave the handle's circular silhouette clear.
        c.paint.pushClip(left-1,top-1,Math.max(0,thumb-radius-left+1),5);
        ramp(c,left,top,trackWidth);
        c.paint.popClip();
        c.paint.pushClip(thumb+radius,top-1,Math.max(0,right-thumb-radius+1),5);
        ramp(c,left,top,trackWidth);
        c.paint.popClip();
        c.paint.fill(thumb-radius,cy-radius,2*radius,2*radius,radius,Theme.TEXT);
        c.paint.fill(thumb-radius+1,cy+1-radius,2*radius-2,2*radius-2,radius-1,color | 0xff000000);
        float direction = expanded ? -1 : 1;
        c.paint.line(x+width-10,cy-direction,x+width-8,cy+direction,.7f,Theme.MUTED);
        c.paint.line(x+width-8,cy+direction,x+width-6,cy-direction,.7f,Theme.MUTED);
    }
    private void ramp(WidgetCanvas c,float left,float top,float trackWidth) {
        if (channel == 0) {
            c.paint.hueGradient(left,top,trackWidth,3);
        } else {
            if (channel == 3) checker(c,left,top,trackWidth,3);
            c.paint.horizontalGradient(left,top,trackWidth,3,1.5f,1.5f,endColor(false),endColor(true));
        }
    }
}
