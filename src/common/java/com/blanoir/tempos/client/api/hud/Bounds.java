package com.blanoir.tempos.client.api.hud;

/** Immutable UI bounds shared by layout, hit testing and HUD editing. */
public record Bounds(double x, double y, double width, double height) {
    public static final Bounds EMPTY = new Bounds(0, 0, 0, 0);

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }

    public double centerX() {
        return x + width / 2.0D;
    }

    public boolean contains(double pointX, double pointY) {
        return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
    }
}
