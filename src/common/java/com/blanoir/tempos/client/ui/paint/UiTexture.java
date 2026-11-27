package com.blanoir.tempos.client.ui.paint;

import java.nio.ByteBuffer;

/** A UI-owned RGBA atlas page. Upload rectangles use physical texels and top-left coordinates. */
public interface UiTexture extends AutoCloseable {
    /** Unknown or externally mutable textures cannot reuse a previous raster result. */
    default long revision() { return -1; }
    void update(int left, int top, int width, int height, ByteBuffer rgba);
    @Override void close();
}
