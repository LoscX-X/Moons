package com.blanoir.tempos.client.ui.paint;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Inter at one physical pixel size. An unsupported string is handed to Minecraft in its entirety. */
public final class UiFont implements AutoCloseable {
    private static final int PAGE_SIZE = 1024;
    private static final int RUN_CACHE_SIZE = 512;
    private static final Run FALLBACK = new Run(new Glyph[0], new float[0], Float.NaN, Float.NaN, Float.NaN, Float.NaN);

    private record Glyph(UiTexture page, float u0, float v0, float u1, float v1, float x, float y,
                         float width, float height, float advance) {}
    private record Run(Glyph[] glyphs, float[] offsets, float width, float left, float right, float inkCenterY) {}

    private final UiBackend backend;
    private final UiFont atlasOwner;
    private final UiFont source;
    private ArrayList<UiFont> sizes;
    private UiFont light;
    private final float logicalSize;
    private final float guiScale, fontScale, ascent, lineHeight;
    private enum Metrics { INTERFACE, SMOOTH_HUD, PIXEL_HUD }
    private final Metrics metrics;
    private final ByteBuffer data;
    private final STBTTFontinfo info;
    private final ArrayList<UiTexture> pages = new ArrayList<>();
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Map<String, Glyph> icons = new HashMap<>();
    private final Map<String, Run> runs = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
            return size() > RUN_CACHE_SIZE;
        }
    };
    private int cursorX, cursorY, rowHeight;
    private boolean closed;

    public UiFont(UiBackend backend, float logicalSize, float guiScale) {
        this(backend, logicalSize, guiScale, "inter-frozen-medium.otf", null, Metrics.INTERFACE);
    }
    /** HUD fonts use em size. The legacy pixel face measures its inter-glyph tracking in HUD pixels. */
    public static UiFont hud(UiBackend backend, float physicalScale, boolean pixelMode) {
        return hudAtEm(backend, (pixelMode ? 8 : 9) * physicalScale, pixelMode);
    }
    public static UiFont hudAtEm(UiBackend backend, float emSize, boolean pixelMode) {
        return new UiFont(backend,emSize,1,
                pixelMode ? "minecraft-ascii.ttf" : "inter-frozen-medium.otf",null,
                pixelMode ? Metrics.PIXEL_HUD : Metrics.SMOOTH_HUD);
    }
    private UiFont(UiBackend backend, float logicalSize, float guiScale, String file, UiFont atlasOwner, Metrics metrics) {
        if (!(guiScale > 0) || !(logicalSize > 0) || !Float.isFinite(guiScale * logicalSize))
            throw new IllegalArgumentException("Invalid UI font size");
        this.backend = backend;
        this.logicalSize = logicalSize;
        this.atlasOwner = atlasOwner;
        this.source = this;
        this.metrics = metrics;
        this.guiScale = guiScale;
        byte[] bytes;
        try (var input = UiFont.class.getResourceAsStream("/assets/tempos/font/" + file)) {
            if (input == null) throw new IllegalStateException("Missing bundled Inter font");
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        info = STBTTFontinfo.calloc();
        try (var stack = MemoryStack.stackPush()) {
            if (!STBTruetype.stbtt_InitFont(info, data)) throw new IllegalStateException("Invalid Inter font");
            fontScale = metrics == Metrics.INTERFACE
                    ? STBTruetype.stbtt_ScaleForPixelHeight(info,logicalSize*guiScale)
                    : STBTruetype.stbtt_ScaleForMappingEmToPixels(info,logicalSize*guiScale);
            var ascender = stack.mallocInt(1);
            var descender = stack.mallocInt(1);
            var gap = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(info, ascender, descender, gap);
            ascent = ascender.get(0) * fontScale / guiScale;
            lineHeight = (ascender.get(0)-descender.get(0)+gap.get(0))*fontScale/guiScale;
        } catch (RuntimeException | Error failure) {
            info.free();
            MemoryUtil.memFree(data);
            throw failure;
        }
    }

    private UiFont(UiFont source, float size) {
        this.source = source;
        metrics = source.metrics;
        atlasOwner = source.atlasOwner == null ? source : source.atlasOwner;
        backend = source.backend;
        data = source.data;
        info = source.info;
        guiScale = source.guiScale;
        logicalSize = size;
        fontScale = metrics == Metrics.INTERFACE
                ? STBTruetype.stbtt_ScaleForPixelHeight(info, size * guiScale)
                : STBTruetype.stbtt_ScaleForMappingEmToPixels(info, size * guiScale);
        ascent = source.ascent * fontScale / source.fontScale;
        lineHeight = source.lineHeight * fontScale / source.fontScale;
    }

    /** NaN asks the caller to measure the whole string with the game font. */
    public float width(String text) { return run(text).width; }
    public float inkLeft(String text) { return run(text).left; }
    public float inkRight(String text) { return run(text).right; }
    public float inkCenterX(String text) { Run r = run(text); return (r.left + r.right) * .5f; }
    public float inkCenterY(String text) { return run(text).inkCenterY; }
    public float size() { return logicalSize; }
    public float ascent() { return ascent; }
    public UiFont atSize(float size) {
        if (size == logicalSize || size == 0) return this;
        if (size == source.logicalSize) return source;
        if (source.sizes == null) source.sizes = new ArrayList<>(4);
        for (int i = 0; i < source.sizes.size(); i++) {
            UiFont cached = source.sizes.get(i);
            if (cached.logicalSize == size) return cached;
        }
        UiFont variant = new UiFont(source, size);
        source.sizes.add(variant);
        return variant;
    }
    public UiFont light() {
        if (source.atlasOwner != null) return this;
        if (source.light == null)
            source.light = new UiFont(backend, source.logicalSize, guiScale, "inter-light.otf", source,Metrics.INTERFACE);
        return source.light.atSize(logicalSize);
    }

    public boolean draw(UiPainter painter, String text, float left, float top, int color) {
        return draw(painter, text, left, top, color, false);
    }

    /** For animated text, color contains the same row phase for the entire shaped run. */
    public boolean draw(UiPainter painter, String text, float left, float top, int color, boolean accent) {
        Run run = run(text);
        if (run == FALLBACK) return false;
        float baseline = Math.round((top + ascent) * guiScale) / guiScale;
        for (int i = 0; i < run.glyphs.length; i++) {
            Glyph glyph = run.glyphs[i];
            if (glyph.page == null) continue;
            // The atlas mask is already antialiased at framebuffer resolution.
            float glyphX = Math.round((left + run.offsets[i] + glyph.x) * guiScale) / guiScale;
            painter.image(glyph.page, glyphX, baseline + glyph.y,
                    glyph.width, glyph.height, glyph.u0, glyph.v0, glyph.u1, glyph.v1, color, accent);
        }
        return true;
    }

    public int pageCount() { return pages.size(); }

    public float hudBaseline(float rowHeight) {
        boolean pixel = metrics == Metrics.PIXEL_HUD;
        return (rowHeight - (pixel ? logicalSize : lineHeight)) * .5f + (pixel ? logicalSize : ascent);
    }

    public void hudText(UiPainter painter,String value,float left,float rowTop,float rowHeight,int color,boolean shadow) {
        hudText(painter,value,left,rowTop,rowHeight,color,shadow ? 150 : 0,null);
    }
    public void hudText(UiPainter painter,String value,float left,float rowTop,float rowHeight,int color,int shadowAlpha,int[] stops) {
        float baseline = rowTop + hudBaseline(rowHeight);
        if (shadowAlpha > 0) {
            float offset = metrics == Metrics.PIXEL_HUD ? logicalSize / 8 : 1;
            draw(painter,value,left+offset,baseline-ascent+offset,Math.min(shadowAlpha,color>>>24)<<24);
        }
        if (stops == null) {
            draw(painter,value,left,baseline-ascent,color);
            return;
        }
        Run run = run(value);
        if (run.width <= 0) return;
        for (int i=0; i<run.glyphs.length; i++) {
            Glyph glyph=run.glyphs[i];
            if (glyph.page==null) continue;
            float x=Math.round(left+run.offsets[i]+glyph.x);
            float end=x+glyph.width, start=x;
            // Split exactly at each gradient stop, including when it falls inside a glyph.
            for (int k=1; k<=stops.length && start<end; k++) {
                float next=k==stops.length ? end : Math.min(end,left+run.width*k/(stops.length-1));
                if (next<=start) continue;
                float u0=glyph.u0+(glyph.u1-glyph.u0)*(start-x)/glyph.width;
                float u1=glyph.u0+(glyph.u1-glyph.u0)*(next-x)/glyph.width;
                painter.glyphGradient(glyph.page,start,Math.round(baseline)+glyph.y,next-start,glyph.height,
                        u0,glyph.v0,u1,glyph.v1,gradient(stops,(start-left)/run.width),gradient(stops,(next-left)/run.width));
                start=next;
            }
        }
    }
    private static int gradient(int[] stops,float fraction) {
        float t=Math.clamp(fraction,0,1)*(stops.length-1);
        int i=Math.min((int)t,stops.length-2), a=stops[i], b=stops[i+1], c=0;
        for(int shift=0;shift<=24;shift+=8){
            int v=a>>>shift & 255, w=b>>>shift & 255;
            c |= Math.round(v+(w-v)*(t-i))<<shift;
        }
        return c;
    }

    private Run run(String text) {
        if (closed) throw new IllegalStateException("UI font is closed");
        Run found = runs.get(text);
        if (found != null) return found;
        int count = text.codePointCount(0, text.length());
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (metrics == Metrics.INTERFACE && STBTruetype.stbtt_FindGlyphIndex(info, cp) == 0) {
                runs.put(text, FALLBACK);
                return FALLBACK;
            }
            i += Character.charCount(cp);
        }
        Glyph[] letters = new Glyph[count];
        float[] offsets = new float[count];
        float width = 0;
        float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        int index = 0, previous = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            Glyph glyph = glyphs.get(cp);
            if (glyph == null) {
                glyph = rasterize(cp);
                glyphs.put(cp, glyph);
            }
            if (index > 0 && metrics == Metrics.INTERFACE)
                width += STBTruetype.stbtt_GetCodepointKernAdvance(info, previous, cp) * fontScale / guiScale;
            if (index > 0 && metrics == Metrics.PIXEL_HUD) width += logicalSize / 32;
            letters[index] = glyph;
            offsets[index++] = width;
            if (glyph.page != null) {
                float gx = width + glyph.x, gy = ascent + glyph.y;
                left = Math.min(left, gx);
                top = Math.min(top, gy);
                right = Math.max(right, gx + glyph.width);
                bottom = Math.max(bottom, gy + glyph.height);
            }
            width += glyph.advance;
            previous = cp;
            i += Character.charCount(cp);
        }
        Run result = new Run(letters, offsets, width, left <= right ? left : 0, left <= right ? right : width,
                top <= bottom ? (top + bottom) / 2 : logicalSize / 2);
        runs.put(text, result);
        return result;
    }

    private Glyph rasterize(int cp) {
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            var xoff = stack.mallocInt(1);
            var yoff = stack.mallocInt(1);
            var advance = stack.mallocInt(1);
            STBTruetype.stbtt_GetCodepointHMetrics(info, cp, advance, null);
            float logicalAdvance = advance.get(0) * fontScale / guiScale;
            ByteBuffer mask = STBTruetype.stbtt_GetCodepointBitmap(info, fontScale, fontScale,
                    cp, width, height, xoff, yoff);
            try {
                int w = width.get(0), h = height.get(0);
                if (mask == null || w == 0 || h == 0)
                    return new Glyph(null, 0, 0, 0, 0, 0, 0, 0, 0, logicalAdvance);
                int stride = (w + 2) * 4;
                ByteBuffer rgba = MemoryUtil.memCalloc(stride * (h + 2));
                try {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int offset = (y + 1) * stride + (x + 1) * 4;
                            rgba.put(offset, (byte) 255).put(offset + 1, (byte) 255)
                                    .put(offset + 2, (byte) 255).put(offset + 3, metrics == Metrics.PIXEL_HUD
                                            ? (byte)((mask.get(y*w+x)&255)>=128 ? 255 : 0) : mask.get(y*w+x));
                        }
                    }
                    return upload(w, h, rgba, xoff.get(0) / guiScale, yoff.get(0) / guiScale, logicalAdvance);
                } finally {
                    MemoryUtil.memFree(rgba);
                }
            } finally {
                if (mask != null) STBTruetype.stbtt_FreeBitmap(mask);
            }
        }
    }

    private Glyph upload(int w, int h, ByteBuffer rgba, float x, float y, float advance) {
        if (atlasOwner != null) return atlasOwner.upload(w, h, rgba, x, y, advance);
        if (w + 2 > PAGE_SIZE || h + 2 > PAGE_SIZE) throw new IllegalArgumentException("UI image exceeds atlas size");
        if (cursorX + w + 2 > PAGE_SIZE) { cursorX = 0; cursorY += rowHeight; rowHeight = 0; }
        if (pages.isEmpty() || cursorY + h + 2 > PAGE_SIZE) {
            pages.add(backend.createTexture(PAGE_SIZE, PAGE_SIZE));
            cursorX = cursorY = rowHeight = 0;
        }
        var page = pages.getLast();
        page.update(cursorX, cursorY, w + 2, h + 2, rgba);
        float u = (cursorX + 1f) / PAGE_SIZE, v = (cursorY + 1f) / PAGE_SIZE;
        cursorX += w + 2;
        rowHeight = Math.max(rowHeight, h + 2);
        return new Glyph(page,u,v,u + (float)w / PAGE_SIZE,v + (float)h / PAGE_SIZE,x,y,w / guiScale,h / guiScale,advance);
    }
    public void drawIcon(UiPainter painter, String id, float x, float y, float size, int tint) {
        drawIcon(painter,id,x,y,size,tint,false);
    }
    public void drawIcon(UiPainter painter, String id, float x, float y, float size, int tint, boolean accent) {
        Glyph icon = icons.get(id);
        if (icon == null) { icon = loadIcon(id); icons.put(id, icon); }
        painter.image(icon.page, x + size * icon.x, y + size * icon.y, size, size, icon.u0, icon.v0, icon.u1, icon.v1, tint, accent);
    }
    private Glyph loadIcon(String id) {
        String path = "/assets/tempos/textures/gui/" + id + ".png.b64";
        try (var raw = UiFont.class.getResourceAsStream(path)) {
            if (raw == null) throw new IllegalArgumentException("Missing category icon " + id);
            var image = ImageIO.read(Base64.getMimeDecoder().wrap(raw));
            int side = Math.clamp(Math.round(guiScale * 14), 14, 84);
            var small = new java.awt.image.BufferedImage(side,side,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var g = small.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image,0,0,side,side,null);
            } finally { g.dispose(); }
            ByteBuffer rgba = MemoryUtil.memCalloc((side + 2) * (side + 2) * 4);
            try {
                int left = side, top = side, right = -1, bottom = -1;
                for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
                    int at = ((y + 1) * (side + 2) + x + 1) * 4;
                    int alpha = small.getRGB(x, y) >>> 24;
                    if (alpha >= 64) {
                        left = Math.min(left, x); right = Math.max(right, x);
                        top = Math.min(top, y); bottom = Math.max(bottom, y);
                    }
                    rgba.put(at, (byte)255).put(at + 1, (byte)255).put(at + 2, (byte)255)
                            .put(at + 3, (byte)alpha);
                }
                return upload(side,side,rgba,(side-left-right-1)/(2f*side),(side-top-bottom-1)/(2f*side),0);
            } finally { MemoryUtil.memFree(rgba); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (sizes != null) for (UiFont sized : sizes) sized.close();
        if (light != null) light.close();
        for (UiTexture page : pages) page.close();
        pages.clear();
        glyphs.clear();
        icons.clear();
        runs.clear();
        if (source == this) {
            info.free();
            MemoryUtil.memFree(data);
        }
    }
}
