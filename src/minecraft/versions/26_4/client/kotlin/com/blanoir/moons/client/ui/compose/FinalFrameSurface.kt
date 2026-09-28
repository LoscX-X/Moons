package com.blanoir.moons.client.ui.compose

import com.blanoir.moons.client.render.VisualPresentation
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import kotlin.math.ceil
import kotlin.math.floor
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect

/** 26.4-snapshot-1 raster Skia layer uploaded through RenderPearl for both OpenGL and Vulkan. */
internal class FinalFrameSurface : AutoCloseable {
    private var surface: RasterFrameBuffer? = null
    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    private var device: Any? = null
    private var width = 0
    private var height = 0
    private var content = PixelBounds.EMPTY
    private var hasFrame = false

    fun render(
        frameWidth: Int,
        frameHeight: Int,
        redraw: Boolean = true,
        contentBounds: (() -> Rect?)? = null,
        draw: (Canvas) -> Unit,
    ) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val target = VisualPresentation.outputTarget() ?: return
        if (target.width != frameWidth || target.height != frameHeight) return
        val currentDevice = RenderSystem.getDevice()
        if (
            surface == null ||
                texture == null ||
                view == null ||
                device !== currentDevice ||
                width != frameWidth ||
                height != frameHeight
        ) {
            close()
            width = frameWidth
            height = frameHeight
            surface = RasterFrameBuffer(width, height)
            texture =
                RenderSystem.getDevice()
                    .createTexture(
                        "Moons Skia overlay",
                        GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM,
                        width,
                        height,
                        1,
                        1,
                    )
            view = RenderSystem.getDevice().createTextureView(texture!!)
            device = currentDevice
        }
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        if (redraw || !hasFrame) {
            rasterizeFrame(draw)
            content =
                if (contentBounds == null) PixelBounds(0, 0, width, height)
                else pixelBounds(contentBounds())
            if (!content.isEmpty) {
                encoder.writeToTexture(
                    texture!!,
                    surface!!.pack(content.left, content.top, content.width, content.height),
                    0,
                    0,
                    content.left,
                    height - content.bottom,
                    content.width,
                    content.height,
                )
            }
            hasFrame = true
        }
        if (content.isEmpty) return
        // The upload and screen quad share target coordinates. Scissor prevents sampling
        // stale pixels outside this frame's raster content.
        VisualPresentation.blend(
            view!!,
            target,
            content.left,
            height - content.bottom,
            content.width,
            content.height,
        )
    }

    private fun rasterizeFrame(draw: (Canvas) -> Unit) {
        val canvas = surface!!.canvas
        if (!content.isEmpty) {
            canvas.save()
            try {
                canvas.clipRect(
                    Rect.makeLTRB(
                        content.left.toFloat(),
                        content.top.toFloat(),
                        content.right.toFloat(),
                        content.bottom.toFloat(),
                    )
                )
                canvas.clear(0)
            } finally {
                canvas.restore()
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
                content = PixelBounds.EMPTY
                hasFrame = false
            }
        }
    }

    private fun pixelBounds(bounds: Rect?): PixelBounds {
        if (bounds == null) return PixelBounds.EMPTY
        return PixelBounds(
            floor(bounds.left).toInt().coerceIn(0, width),
            floor(bounds.top).toInt().coerceIn(0, height),
            ceil(bounds.right).toInt().coerceIn(0, width),
            ceil(bounds.bottom).toInt().coerceIn(0, height),
        )
    }

    override fun close() {
        view?.close()
        texture?.close()
        surface?.close()
        view = null
        device = null
        texture = null
        surface = null
        width = 0
        height = 0
        content = PixelBounds.EMPTY
        hasFrame = false
    }

    private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int
            get() = right - left

        val height: Int
            get() = bottom - top

        val isEmpty: Boolean
            get() = width <= 0 || height <= 0

        companion object {
            val EMPTY = PixelBounds(0, 0, 0, 0)
        }
    }
}
