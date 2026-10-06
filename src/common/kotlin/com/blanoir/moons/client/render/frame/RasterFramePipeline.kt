package com.blanoir.moons.client.render.frame

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect

/** One retained raster lifecycle, independent of Blaze3D/RenderPearl GPU types. */
internal abstract class RasterFramePipeline<T : Any> : AutoCloseable {
    protected abstract val deviceIdentity: Any
    abstract val ready: Boolean

    protected abstract fun outputTarget(width: Int, height: Int): T?

    protected abstract fun create(width: Int, height: Int)

    protected abstract fun upload(
        surface: RasterFrameBuffer,
        regions: List<RasterRegion>,
        height: Int,
    )

    protected abstract fun blend(target: T, region: RasterRegion, height: Int)

    protected abstract fun closeGpuResources()

    protected abstract fun resetGpuResources()

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
        val target = outputTarget(frameWidth, frameHeight) ?: return
        val currentDevice = deviceIdentity
        if (
            surface == null ||
                !ready ||
                device !== currentDevice ||
                width != frameWidth ||
                height != frameHeight
        ) {
            close()
            width = frameWidth
            height = frameHeight
            surface = RasterFrameBuffer(width, height)
            create(width, height)
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
            if (content.isNotEmpty()) upload(surface!!, content, height)
            hasFrame = true
        }
        // Upload and screen quad share coordinates; scissor excludes stale texture pixels.
        for (region in content) blend(target, region, height)
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
        closeGpuResources()
        surface?.close()
        resetGpuResources()
        device = null
        surface = null
        width = 0
        height = 0
        content = emptyList<RasterRegion>()
        hasFrame = false
    }
}
