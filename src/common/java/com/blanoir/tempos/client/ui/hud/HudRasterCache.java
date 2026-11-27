package com.blanoir.tempos.client.ui.hud;

import com.blanoir.tempos.client.ui.paint.UiBatch;
import com.blanoir.tempos.client.ui.paint.UiPainter;
import java.lang.ref.WeakReference;

/** Two recording buffers keep the preceding frame intact without copying its vertices. */
public final class HudRasterCache {
    private final UiPainter[] painters = {new UiPainter(), new UiPainter()};
    private int next;
    private UiBatch previous;
    private Object surface;
    private WeakReference<Object> context;

    public UiPainter painter() { return painters[next]; }

    public boolean canReuse(UiBatch batch, Object attachment, Object world) {
        return attachment != null && attachment == surface && context != null && context.refersTo(world)
                && batch.rangeCount() != 0 && batch.sameRasterAs(previous);
    }

    /** Called after a successful draw or a matching raster reuse, before recording another frame. */
    public void remember(UiBatch batch, Object attachment, Object world) {
        previous = batch;
        surface = attachment;
        if (context == null || !context.refersTo(world)) context = new WeakReference<>(world);
        next = 1 - next;
    }

    public void reset() {
        previous = null;
        surface = null;
        context = null;
        next = 0;
    }
}
