package com.blanoir.moons.client.ui.compose

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect

/** One retained raster lifecycle, independent of Blaze3D/RenderPearl GPU types. */
internal open class RasterFramePipeline<T : Any>(private val backend: OverlayBackend<T>) :
    AutoCloseable {
    private var surface: RasterFrameBuffer? = null
    private var device: Any? = null
    private var width = 0
    private var height = 0
    private var content = emptyList<RasterRegion>()
    private var hasFrame = false

    fun render(
        frameWidth: Int,
        frameHeight: Int,
        redraw: Boolean = true,
        contentBounds: (() -> Rect?)? = null,
        contentRegions: (() -> List<Rect>)? = null,
        draw: (Canvas) -> Unit,
    ) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val target = backend.outputTarget(frameWidth, frameHeight) ?: return
        val currentDevice = backend.deviceIdentity
        if (
            surface == null ||
                !backend.ready ||
                device !== currentDevice ||
                width != frameWidth ||
                height != frameHeight
        ) {
            close()
            width = frameWidth
            height = frameHeight
            surface = RasterFrameBuffer(width, height)
            backend.create(width, height)
            device = currentDevice
        }
        if (redraw || !hasFrame) {
            rasterizeFrame(draw)
            content =
                when {
                    contentRegions != null -> RasterRegion.collect(contentRegions(), width, height)
                    contentBounds != null ->
                        RasterRegion.collect(listOfNotNull(contentBounds()), width, height)
                    else -> listOf(RasterRegion(0, 0, width, height))
                }
            if (content.isNotEmpty()) backend.upload(surface!!, content, height)
            hasFrame = true
        }
        // Upload and screen quad share coordinates; scissor excludes stale texture pixels.
        for (region in content) backend.blend(target, region, height)
    }

    private fun rasterizeFrame(draw: (Canvas) -> Unit) {
        val canvas = surface!!.canvas
        for (region in content) {
            val saved = canvas.save()
            try {
                canvas.clipRect(region.rect())
                canvas.clear(0)
            } finally {
                canvas.restoreToCount(saved)
            }
        }
        val saved = canvas.save()
        var complete = false
        try {
            draw(canvas)
            complete = true
        } finally {
            canvas.restoreToCount(saved)
            if (!complete) {
                // A failed callback may draw outside the previous content bounds.
                canvas.clear(0)
                content = emptyList<RasterRegion>()
                hasFrame = false
            }
        }
    }

    override fun close() {
        backend.close()
        surface?.close()
        backend.reset()
        device = null
        surface = null
        width = 0
        height = 0
        content = emptyList<RasterRegion>()
        hasFrame = false
    }
}
