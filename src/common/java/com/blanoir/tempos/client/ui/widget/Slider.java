package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** A stepped value, or a pair of stepped values with a draggable interval. */
public final class Slider extends Widget {
    private static final float INSET = 5;
    @FunctionalInterface public interface Change { void set(double low, double high); }
    private enum Handle { NONE, LOW, HIGH, BOTH }
    private final String title;
    private final SettingLabel caption;
    private final BigDecimal first, step;
    private final int places;
    private final long firstUnits, stepUnits;
    private final double divisor;
    private final int last;
    private final boolean range;
    private final Change change;
    private final Anim lowPosition, highPosition;
    private final Anim lowHover = new Anim(0), highHover = new Anim(0);
    private float lowRadius=3.5f, highRadius=3.5f, numericLeft, numericRightStart;
    private double wheelCarry;
    private boolean wheelUpper;
    private int low, high, grabbed, startLow, startHigh;
    private float lowDraw, highDraw;
    private Handle handle = Handle.NONE;
    private boolean releaseOnly, pending;
    private String valueHint = "";
    private final Digits lowDigits, highDigits;

    public Slider(String title, double min, double max, double increment, double low, double high, boolean range, Change change) {
        this.title = title;
        caption = new SettingLabel(title);
        this.range = range;
        this.change = change;
        first = BigDecimal.valueOf(min);
        step = BigDecimal.valueOf(increment);
        if (!(max > min && increment > 0)) throw new IllegalArgumentException("Invalid slider limits");
        last = BigDecimal.valueOf(max).subtract(first).divide(step, 0, RoundingMode.FLOOR).intValueExact();
        if (last == 0) throw new IllegalArgumentException("Slider has no step");
        places = Math.max(0, Math.max(first.stripTrailingZeros().scale(), step.stripTrailingZeros().scale()));
        lowDigits = new Digits(places);
        highDigits = new Digits(places);
        firstUnits = first.movePointRight(places).longValueExact();
        stepUnits = step.movePointRight(places).longValueExact();
        Math.addExact(firstUnits, Math.multiplyExact((long)last, stepUnits));
        divisor = Math.pow(10, places);
        this.low = index(low);
        this.high = range ? Math.max(this.low, index(high)) : this.low;
        lowDraw = this.low; highDraw = this.high;
        lowPosition = new Anim(lowDraw); highPosition = new Anim(highDraw);
        updateLabel();
    }
    private int index(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite slider value");
        BigDecimal ticks = BigDecimal.valueOf(value).subtract(first).divide(step, 0, RoundingMode.HALF_UP);
        return ticks.max(BigDecimal.ZERO).min(BigDecimal.valueOf(last)).intValue();
    }
    private BigDecimal number(int index) { return first.add(step.multiply(BigDecimal.valueOf(index))).stripTrailingZeros(); }
    private void updateLabel() {
        lowDigits.set(firstUnits + stepUnits * low, places);
        if (range) highDigits.set(firstUnits + stepUnits * high, places);
    }
    private boolean update(int a, int b, long now) {
        a = Math.clamp(a, 0, last);
        b = range ? Math.clamp(b, a, last) : a;
        if (low == a && high == b) return false;
        low = a; high = b;
        updateLabel();
        lowPosition.to(a, now, Theme.HANDLE_MS);
        highPosition.to(b, now, Theme.HANDLE_MS);
        repaint();
        return true;
    }
    private void set(int a, int b, long now) {
        if (!update(a, b, now)) return;
        valueHint = "";
        if (releaseOnly && handle != Handle.NONE) pending = true;
        else commit();
    }
    private void commit() {
        change.set((firstUnits + stepUnits * low) / divisor, (firstUnits + stepUnits * high) / divisor);
    }
    /** Scrub locally while dragging; changing a typed or scrolled value still commits immediately. */
    public void commitOnRelease() { releaseOnly = true; }
    public void valueHint(String text) {
        if (handle == Handle.NONE && !text.equals(valueHint)) { valueHint=text; repaint(); }
    }
    public void sync(double a, double b) {
        if (handle != Handle.NONE) return;
        update(index(a), range ? index(b) : 0, System.nanoTime());
    }
    private float pixel(float index) { return x + INSET + index / last * Math.max(1, width - INSET * 2); }
    private int pointer(float px) {
        double unit = Math.clamp((px - x - INSET) / Math.max(1, width - INSET * 2), 0, 1);
        return (int)Math.floor(unit * last + .5);
    }
    @Override public float preferredHeight() { return 23 + caption.extraHeight(); }
    @Override protected boolean lightText() { return true; }
    @Override protected float textSize() { return Theme.Floating.DETAIL_SIZE; }
    @Override protected boolean focusable() { return true; }
    private boolean near(float value) {
        if (!hovered()) return false;
        float dx=tree.pointerX()-pixel(value), dy=tree.pointerY()-(y+height-5);
        return dx*dx+dy*dy <= 20;
    }
    @Override protected void tick(long now) {
        float a = lowPosition.value(now), b = highPosition.value(now);
        if (a != lowDraw || b != highDraw) { lowDraw = a; highDraw = b; repaint(); }
        lowHover.to(near(a) || handle==Handle.LOW || handle==Handle.BOTH ? 1 : 0,now,Theme.HOVER_MS);
        highHover.to(range && (near(b) || handle==Handle.HIGH || handle==Handle.BOTH) ? 1 : 0,now,Theme.HOVER_MS);
        float lr=3.5f+.5f*lowHover.value(now), hr=3.5f+.5f*highHover.value(now);
        if (lr!=lowRadius || hr!=highRadius) { lowRadius=lr;highRadius=hr;repaint(); }
    }
    @Override protected void paint(WidgetCanvas c) {
        float middle = y + (height - 8) / 2;
        float numberTop = c.centeredY("0123456789", middle);
        float rightEdge = x + width - INSET;
        if (range) {
            numericRightStart = highDigits.draw(c, rightEdge, numberTop);
            rightEdge = numericRightStart - c.width(" – ");
            c.label(" – ", rightEdge, c.centeredY(" – ",middle), Theme.TEXT);
        }
        if (valueHint.isEmpty()) numericLeft = lowDigits.draw(c, rightEdge, numberTop);
        else {
            numericLeft = c.rightX(valueHint,rightEdge);
            c.label(valueHint,numericLeft,c.centeredY(valueHint,middle),Theme.MUTED);
        }
        float leftEdge = x+INSET;
        if(caption.fit(c,Math.max(1,numericLeft-leftEdge-4))) relayout();
        caption.draw(c,leftEdge,middle,Theme.Floating.MODULE);
        float trackY = y + height - 6;
        c.paint.fill(pixel(0), trackY, pixel(last) - pixel(0), 2, 1, Theme.TRACK);
        float left = range ? pixel(lowDraw) : pixel(0), right = pixel(range ? highDraw : lowDraw);
        c.paint.accentFill(left, trackY, right - left, 2, 1, 255, 1);
        float rr=range ? highRadius : lowRadius;
        c.paint.accentFill(right-rr,trackY+1-rr,rr*2,rr*2,rr,255,1);
        if (range) c.paint.accentFill(left-lowRadius,trackY+1-lowRadius,lowRadius*2,lowRadius*2,lowRadius,255,1);
    }
    @Override protected boolean input(UiEvent e) {
        if (e.kind == UiEvent.Kind.PRESS && e.button == 0) {
            boolean upper = range && (low == high ? e.x >= pixel(low)
                    : Math.abs(e.x - pixel(high)) < Math.abs(e.x - pixel(low)));
            if (e.y < y+height-11) {
                if (e.doubleClick && e.x >= numericLeft-2) {
                    boolean editUpper = range && e.x >= numericRightStart;
                    edit(editUpper);
                }
                return true;
            }
            grabbed = pointer(e.x); startLow = low; startHigh = high;
            handle = range && e.x > pixel(low) + 4 && e.x < pixel(high) - 4 ? Handle.BOTH : upper ? Handle.HIGH : Handle.LOW;
            move(e); return true;
        }
        if (e.kind == UiEvent.Kind.MOVE && handle != Handle.NONE) { move(e); return true; }
        if (e.kind == UiEvent.Kind.RELEASE && handle != Handle.NONE) {
            handle = Handle.NONE;
            if (pending) { pending=false; commit(); }
            repaint(); return true;
        }
        if (e.kind == UiEvent.Kind.SCROLL && e.wheel != 0) {
            boolean upper=range && Math.abs(e.x-pixel(high))<Math.abs(e.x-pixel(low));
            if(upper!=wheelUpper || e.wheel*wheelCarry<0) wheelCarry=0;
            wheelUpper=upper;
            wheelCarry+=e.wheel*(e.shift ? .25 : 1);
            int delta=(int)wheelCarry;
            wheelCarry-=delta;
            if (upper) set(low, high + delta, e.time);
            else set(range ? Math.min(high, low + delta) : low + delta, high, e.time);
            return true;
        }
        return false;
    }
    private void move(UiEvent e) {
        int at = pointer(e.x);
        if (handle == Handle.BOTH) {
            int delta = Math.clamp(at - grabbed, -startLow, last - startHigh);
            set(startLow + delta, startHigh + delta, e.time);
        } else if (handle == Handle.HIGH) set(low, Math.max(low, at), e.time);
        else set(range ? Math.min(at, high) : at, high, e.time);
    }
    private void edit(boolean upper) {
        WidgetTree owner = tree;
        TextField field = new TextField(title, value -> {}) {
            @Override protected boolean input(UiEvent e) {
                if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER) {
                    try {
                        int next = index(Double.parseDouble(value().trim()));
                        if (upper) set(low, Math.max(low, next), e.time);
                        else if (!valueHint.isEmpty() && next==low) { valueHint=""; commit(); repaint(); }
                        else set(range ? Math.min(next, high) : next, high, e.time);
                        owner.closePopup();
                    } catch (IllegalArgumentException ignored) {
                        // Keep the input so the user can correct the number.
                    }
                    return true;
                }
                return super.input(e);
            }
        };
        field.useSettingStyle();
        float fieldWidth = Math.max(1, Math.min(37, width - 2 * INSET));
        field.bounds(x + width - INSET - fieldWidth, y, fieldWidth, 14);
        field.value(number(upper ? high : low).toPlainString());
        owner.popup(field);
        owner.focus(field);
        field.selectAll();
    }
    @Override protected void captureLost() { handle = Handle.NONE; pending=false; }

    /** Records the few decimal glyphs directly so dragging never formats a new string. */
    private static final class Digits {
        private static final String[] GLYPHS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "-"};
        private final byte[] reversed;
        private int size;
        Digits(int places) { reversed = new byte[22 + places]; }
        void set(long units, int decimals) {
            size = 0;
            while (decimals > 0 && units % 10 == 0) { units /= 10; decimals--; }
            boolean negative = units < 0;
            long remaining = negative ? units : -units;
            int written = 0;
            do {
                if (decimals > 0 && written == decimals) reversed[size++] = 10;
                reversed[size++] = (byte)-(remaining % 10);
                remaining /= 10;
                written++;
            } while (remaining != 0 || written <= decimals);
            if (negative) reversed[size++] = 11;
        }
        float draw(WidgetCanvas c, float right, float top) {
            String last = GLYPHS[reversed[0]];
            right = c.rightX(last, right) + c.width(last);
            for (int i = 0; i < size; i++) {
                String glyph = GLYPHS[reversed[i]];
                right -= c.width(glyph);
                c.label(glyph, right, top, Theme.TEXT);
            }
            return right;
        }
    }
}
