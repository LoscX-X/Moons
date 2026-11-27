package com.blanoir.tempos.client.ui.host;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.ui.paint.UiBatch;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Composite just the painted portion of a bottom-up, premultiplied GUI attachment. */
public final class LayerPresentation {
    private LayerPresentation() {}

    public static void blit(GuiGraphicsExtractor graphics, Identifier texture, UiBatch batch,
            int guiWidth, int guiHeight, int shift, int tint) {
        if (batch.rangeCount() == 0 || guiWidth <= 0 || guiHeight <= 0) return;
        int w = batch.width(), h = batch.height();
        int sx = w / guiWidth, sy = h / guiHeight;
        if (sx > 0 && sy > 0 && sx * guiWidth == w && sy * guiHeight == h) {
            int left = batch.contentLeft() / sx, top = batch.contentTop() / sy;
            int right = Math.ceilDiv(batch.contentRight(), sx), bottom = Math.ceilDiv(batch.contentBottom(), sy);
            int width = right - left, height = bottom - top;
            graphics.blit(MinecraftClientAccess.guiPremultipliedPipeline(), texture, left, top + shift,
                    left * sx, h - top * sy, width, height, width * sx, -height * sy, w, h, tint);
            return;
        }
        // A resized window may have a partial last GUI pixel. Preserve the original UV mapping.
        int left = Math.max(0, (int)((long)batch.contentLeft() * guiWidth / w) - 1);
        int top = Math.max(0, (int)((long)batch.contentTop() * guiHeight / h) - 1);
        int right = Math.min(guiWidth, (int)Math.ceilDiv((long)batch.contentRight() * guiWidth, w) + 1);
        int bottom = Math.min(guiHeight, (int)Math.ceilDiv((long)batch.contentBottom() * guiHeight, h) + 1);
        graphics.enableScissor(left, top + shift, right, bottom + shift);
        graphics.blit(MinecraftClientAccess.guiPremultipliedPipeline(), texture, 0, shift, 0, h,
                guiWidth, guiHeight, w, -h, w, h, tint);
        graphics.disableScissor();
    }
}
