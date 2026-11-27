package com.blanoir.tempos.client.ui.paint;

import com.blanoir.tempos.client.ui.theme.Theme;
import java.util.Arrays;

/** Records UI geometry in logical GUI pixels. Adjacent compatible draws share one GPU range. */
public final class UiPainter {
    private static final float TAU = (float) (Math.PI * 2);
    private final UiBatch batch = new UiBatch();
    private int[] clips = new int[32];
    private int clip;
    private static final Theme DEFAULT_THEME = new Theme();
    private Theme theme = DEFAULT_THEME;

    public void begin(int framebufferWidth, int framebufferHeight, float guiScale) {
        begin(framebufferWidth, framebufferHeight, guiScale, DEFAULT_THEME);
    }
    public void begin(int framebufferWidth, int framebufferHeight, float guiScale, Theme theme) {
        this.theme = theme;
        batch.reset(framebufferWidth, framebufferHeight, guiScale);
        clip = 0;
        clips[0] = clips[1] = 0;
        clips[2] = framebufferWidth;
        clips[3] = framebufferHeight;
    }

    public void pushClip(float x, float y, float width, float height) {
        int next = clip + 4;
        if (next + 4 > clips.length) clips = Arrays.copyOf(clips, clips.length * 2);
        float scale = batch.scale();
        clips[next] = Math.max(clips[clip], (int) Math.floor(x * scale));
        clips[next + 1] = Math.max(clips[clip + 1], (int) Math.floor(y * scale));
        clips[next + 2] = Math.min(clips[clip + 2], (int) Math.ceil((x + width) * scale));
        clips[next + 3] = Math.min(clips[clip + 3], (int) Math.ceil((y + height) * scale));
        clip = next;
    }

    public void popClip() {
        if (clip == 0) throw new IllegalStateException("No UI clip to pop");
        clip -= 4;
    }

    public int clipLeft() { return clips[clip]; }
    public int clipTop() { return clips[clip + 1]; }
    public int clipRight() { return clips[clip + 2]; }
    public int clipBottom() { return clips[clip + 3]; }
    public float scale() { return batch.scale(); }

    public void fill(float x, float y, float w, float h, float radius, int color) {
        rounded(x, y, w, h, radius, radius, radius, radius, color, color);
    }

    public void rounded(float x, float y, float w, float h, float topLeft, float topRight,
            float bottomRight, float bottomLeft, int topColor, int bottomColor) {
        shape(x, y, w, h, 0, 0, topLeft, topRight, bottomRight, bottomLeft, topColor, bottomColor);
    }

    public void outline(float x, float y, float w, float h, float radius, float stroke, int color) {
        if (stroke > 0) shape(x, y, w, h, 1, stroke, radius, radius, radius, radius, color, color);
    }
    /** A rotated SDF capsule keeps fine strokes smooth at every GUI scale. */
    public void line(float x1,float y1,float x2,float y2,float stroke,int color) {
        float dx=x2-x1, dy=y2-y1, length=(float)Math.hypot(dx,dy);
        if(length==0 || stroke<=0 || clipLeft()>=clipRight() || clipTop()>=clipBottom()) return;
        float nx=dx/length, ny=dy/length, midX=(x1+x2)/2, midY=(y1+y2)/2;
        float r=stroke/2, half=length/2+r, aa=1/batch.scale();
        float extentX = Math.abs(nx)*(half+aa) + Math.abs(ny)*(r+aa);
        float extentY = Math.abs(ny)*(half+aa) + Math.abs(nx)*(r+aa);
        if (!batch.reserveQuad(null,clipLeft(),clipTop(),clipRight(),clipBottom(),
                midX-extentX,midY-extentY,midX+extentX,midY+extentY)) return;
        for(int i=0;i<6;i++) {
            float u=(i==1 || i==2 || i==4 ? 1 : -1)*(half+aa);
            float v=(i==2 || i==4 || i==5 ? 1 : -1)*(r+aa);
            batch.vertex(midX+nx*u-ny*v,midY+ny*u+nx*v,u,v,color,half,r,0,0,r,r,r,r);
        }
    }

    public void accentFill(float x, float y, float w, float h, float radius, int alpha, float brightness) {
        accentGradient(x, y, w, h, radius, alpha, alpha, brightness);
    }

    public void accentGradient(float x, float y, float w, float h, float radius, int topAlpha, int bottomAlpha,
            float brightness) {
        shape(x, y, w, h, theme.rgb() ? 16 : 0, 0, radius, radius, radius, radius,
                theme.accentTint(y, topAlpha, brightness), theme.accentTint(y, bottomAlpha, brightness));
    }

    public void accentOutline(float x, float y, float w, float h, float radius, float stroke, int alpha, float brightness) {
        if (stroke <= 0) return;
        int color = theme.accentTint(y, alpha, brightness);
        shape(x, y, w, h, theme.rgb() ? 17 : 1, stroke, radius, radius, radius, radius, color, color);
    }

    public void horizontalGradient(float x, float y, float w, float h, float leftRadius, float rightRadius,
            int left, int right) {
        if (w <= 0 || h <= 0) return;
        float pad = 1 / batch.scale(), hw = w * .5f, hh = h * .5f;
        quad(null, x - pad, y - pad, w + 2 * pad, h + 2 * pad, -hw - pad, -hh - pad, hw + pad, hh + pad,
                left, right, hw, hh, 0, 0, leftRadius, rightRadius, rightRadius, leftRadius, true);
    }

    public void hueGradient(float x, float y, float w, float h) {
        float radius = h / 2;
        shape(x, y, w, h, 4, 0, radius, radius, radius, radius, -1, -1);
    }

    public void shadow(float x, float y, float w, float h, float radius, float blur, int color) {
        if (blur > 0) shape(x, y, w, h, 2, blur, radius, radius, radius, radius, color, color);
    }

    /** Angles are clockwise radians from the right. A full turn makes a ring. */
    public void arc(float cx, float cy, float radius, float stroke, float start, float sweep, int color) {
        if (radius <= 0 || stroke <= 0 || sweep == 0) return;
        if (sweep < 0) {
            start += sweep;
            sweep = -sweep;
        }
        shape(cx - radius, cy - radius, radius * 2, radius * 2, 3, stroke,
                start, Math.min(sweep, TAU), 0, 0, color, color);
    }

    public void image(UiTexture texture, float x, float y, float width, float height,
            float u0, float v0, float u1, float v1, int tint) {
        image(texture, x, y, width, height, u0, v0, u1, v1, tint, false);
    }
    void image(UiTexture texture, float x, float y, float width, float height,
            float u0, float v0, float u1, float v1, int tint, boolean accent) {
        if (texture == null) throw new IllegalArgumentException("Missing UI texture");
        quad(texture, x, y, width, height, u0, v0, u1, v1, tint, tint, 0, 0, accent ? 16 : 0, 0, 0, 0, 0, 0, false);
    }

    void glyphGradient(UiTexture texture,float x,float y,float w,float h,float u0,float v0,float u1,float v1,int left,int right) {
        quad(texture,x,y,w,h,u0,v0,u1,v1,left,right,0,0,0,0,0,0,0,0,true);
    }
    private void shape(float x, float y, float w, float h, int kind, float line,
            float a, float b, float c, float d, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        float pad = 1 / batch.scale() + (kind == 2 ? line : 0);
        float halfWidth = w * .5f, halfHeight = h * .5f;
        quad(null, x - pad, y - pad, w + 2 * pad, h + 2 * pad,
                -halfWidth - pad, -halfHeight - pad, halfWidth + pad, halfHeight + pad,
                top, bottom, halfWidth, halfHeight, kind, line, a, b, c, d, false);
    }

    private void quad(UiTexture texture, float x, float y, float w, float h,
            float u0, float v0, float u1, float v1, int firstColor, int secondColor,
            float halfWidth, float halfHeight, int kind, float line, float a, float b, float c, float d,
            boolean horizontal) {
        if (w <= 0 || h <= 0 || clips[clip] >= clips[clip + 2] || clips[clip + 1] >= clips[clip + 3]) return;
        if (!batch.reserveQuad(texture, clips[clip], clips[clip + 1], clips[clip + 2], clips[clip + 3],
                x, y, x + w, y + h)) return;
        if (kind >= 16) batch.accent(theme.clock());
        for (int i = 0; i < 6; i++) {
            boolean right = i == 1 || i == 2 || i == 4;
            boolean bottom = i == 2 || i == 4 || i == 5;
            batch.vertex(right ? x + w : x, bottom ? y + h : y, right ? u1 : u0, bottom ? v1 : v0,
                    (horizontal ? right : bottom) ? secondColor : firstColor, halfWidth, halfHeight, kind, line, a, b, c, d);
        }
    }

    public UiBatch end() {
        if (clip != 0) throw new IllegalStateException("Unbalanced UI clips");
        batch.finish();
        return batch;
    }
}
