package com.blanoir.tempos.client.ui.paint;

import com.blanoir.tempos.client.ui.theme.AccentClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Interleaved triangles and ordered draw ranges, retained until the UI needs repainting. */
public final class UiBatch {
    public static final int STRIDE = 56;

    public static final class Range {
        private int first, count, left, top, right, bottom;
        private UiTexture texture;
        private long textureRevision;

        public int first() { return first; }
        public int count() { return count; }
        public int left() { return left; }
        public int top() { return top; }
        public int right() { return right; }
        public int bottom() { return bottom; }
        public UiTexture texture() { return texture; }
    }

    private ByteBuffer bytes = allocate(STRIDE * 6 * 128);
    private Range[] ranges = new Range[16];
    private int rangeCount;
    private int width, height;
    private float minX, minY, maxX, maxY;
    private float scale, toClipX, toClipY;
    private long revision;
    private AccentClock accent;

    private static ByteBuffer allocate(int capacity) {
        return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
    }

    void reset(int width, int height, float scale) {
        if (width <= 0 || height <= 0 || !(scale > 0) || !Float.isFinite(scale))
            throw new IllegalArgumentException("Invalid UI viewport");
        for (int i = 0; i < rangeCount; i++) ranges[i].texture = null;
        bytes.clear();
        this.width = width;
        this.height = height;
        minX = width;
        minY = height;
        maxX = maxY = 0;
        this.scale = scale;
        toClipX = 2 * scale / width;
        toClipY = -2 * scale / height;
        rangeCount = 0;
        accent = null;
        revision++;
    }

    boolean reserveQuad(UiTexture texture, int left, int top, int right, int bottom,
            float x0, float y0, float x1, float y1) {
        float l = Math.max(left, x0 * scale), t = Math.max(top, y0 * scale);
        float r = Math.min(right, x1 * scale), b = Math.min(bottom, y1 * scale);
        if (l >= r || t >= b) return false;
        minX = Math.min(minX, l);
        minY = Math.min(minY, t);
        maxX = Math.max(maxX, r);
        maxY = Math.max(maxY, b);
        Range range = rangeCount == 0 ? null : ranges[rangeCount - 1];
        if (range == null || range.texture != texture || range.left != left || range.top != top
                || range.right != right || range.bottom != bottom) {
            if (rangeCount == ranges.length) ranges = Arrays.copyOf(ranges, rangeCount * 2);
            range = ranges[rangeCount];
            if (range == null) ranges[rangeCount] = range = new Range();
            rangeCount++;
            range.first = bytes.position() / STRIDE;
            range.count = 0;
            range.texture = texture;
            range.left = left;
            range.top = top;
            range.right = right;
            range.bottom = bottom;
        }
        range.count += 6;
        if (bytes.remaining() < 6 * STRIDE) {
            ByteBuffer next = allocate(Math.multiplyExact(bytes.capacity(), 2));
            next.put(bytes.flip());
            bytes = next;
        }
        return true;
    }

    void vertex(float x, float y, float u, float v, int argb, float halfWidth, float halfHeight,
            float shape, float width, float a, float b, float c, float d) {
        bytes.putFloat(x * toClipX - 1).putFloat(1 + y * toClipY).putFloat(0);
        bytes.putFloat(u).putFloat(v);
        bytes.put((byte) (argb >>> 16)).put((byte) (argb >>> 8))
                .put((byte) argb).put((byte) (argb >>> 24));
        bytes.putFloat(halfWidth).putFloat(halfHeight).putFloat(shape).putFloat(width);
        bytes.putFloat(a).putFloat(b).putFloat(c).putFloat(d);
    }

    void finish() {
        bytes.flip();
        for (int i = 0; i < rangeCount; i++) {
            Range range = ranges[i];
            range.textureRevision = range.texture == null ? 0 : range.texture.revision();
        }
    }

    /** Exact render-input equality; unknown texture contents and animated uniforms never match. */
    public boolean sameRasterAs(UiBatch previous) {
        if (previous == null || accent != null || previous.accent != null
                || width != previous.width || height != previous.height || scale != previous.scale
                || rangeCount != previous.rangeCount || bytes.limit() != previous.bytes.limit()) return false;
        for (int i = 0; i < rangeCount; i++) {
            Range a = ranges[i], b = previous.ranges[i];
            if (a.first != b.first || a.count != b.count || a.texture != b.texture
                    || a.textureRevision < 0 || a.textureRevision != b.textureRevision
                    || a.left != b.left || a.top != b.top || a.right != b.right || a.bottom != b.bottom)
                return false;
        }
        return bytes.position(0).mismatch(previous.bytes.position(0)) == -1;
    }
    void accent(AccentClock clock) { accent = clock; }
    public AccentClock accent() { return accent; }

    /** The GPU uploader borrows the buffer for the duration of the call. */
    public ByteBuffer vertices() { return bytes.position(0); }
    public int vertexCount() { return bytes.limit() / STRIDE; }
    public int width() { return width; }
    public int height() { return height; }
    public int contentLeft() { return (int)Math.floor(minX); }
    public int contentTop() { return (int)Math.floor(minY); }
    public int contentRight() { return (int)Math.ceil(maxX); }
    public int contentBottom() { return (int)Math.ceil(maxY); }
    public float scale() { return scale; }
    public long revision() { return revision; }
    public int rangeCount() { return rangeCount; }
    public Range range(int index) { return ranges[index]; }
}
