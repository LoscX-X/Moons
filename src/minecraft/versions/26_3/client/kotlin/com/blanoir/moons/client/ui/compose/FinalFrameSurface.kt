package com.blanoir.moons.client.ui.compose

import com.blanoir.moons.client.access.GameAccess
import com.blanoir.moons.client.access.MinecraftClientAccess
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import java.nio.ByteBuffer
import java.util.Optional
import kotlin.math.ceil
import kotlin.math.floor
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.lwjgl.system.MemoryUtil

/** 26.3 raster Skia layer uploaded through RenderPearl for both OpenGL and Vulkan. */
internal class FinalFrameSurface : AutoCloseable {
    private var surface: Surface? = null
    private var pixels: ByteBuffer? = null
    private var uploadPixels: ByteBuffer? = null
    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
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
        val target = MinecraftClientAccess.mainRenderTarget(Minecraft.getInstance())
        val colorView = target.colorTextureView ?: return
        if (surface == null || width != frameWidth || height != frameHeight) {
            close()
            width = frameWidth
            height = frameHeight
            val buffer =
                MemoryUtil.memCalloc(Math.multiplyExact(Math.multiplyExact(width, height), 4))
            pixels = buffer
            surface =
                Surface.makeRasterDirect(
                    ImageInfo(
                        width,
                        height,
                        ColorType.RGBA_8888,
                        ColorAlphaType.PREMUL,
                        ColorSpace.sRGB,
                    ),
                    MemoryUtil.memAddress(buffer),
                    width * 4,
                )
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
                    packPixels(content),
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
        encoder.createRenderPass({ "Moons Skia overlay" }, colorView, Optional.empty()).use { pass
            ->
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline))
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform(
                "InSampler",
                view!!,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
            )
            // The screen-quad UVs and texture upload use the same target coordinates.
            // Never sample stale or uninitialized texels outside this frame's content.
            pass.enableScissor(
                content.left,
                height - content.bottom,
                content.width,
                content.height,
            )
            pass.draw(3, 1, 0, 0)
        }
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
        draw(canvas)
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

    private fun packPixels(bounds: PixelBounds): ByteBuffer {
        val rowBytes = bounds.width * 4
        val byteCount = Math.multiplyExact(rowBytes, bounds.height)
        var upload = uploadPixels
        if (upload == null || upload.capacity() < byteCount) {
            val capacity =
                minOf(pixels!!.capacity().toLong(), (byteCount.toLong() + 65_535L) and -65_536L)
                    .toInt()
            val replacement = MemoryUtil.memAlloc(capacity)
            upload?.let { MemoryUtil.memFree(it) }
            uploadPixels = replacement
            upload = replacement
        }
        upload.clear()
        upload.limit(byteCount)
        // Keep font rasterization top-down. Reflecting the canvas breaks aliased glyphs
        // at fractional scales. Pack only the visible rectangle, reversing rows once
        // into reusable upload memory instead of swapping the entire framebuffer.
        val stride = width * 4L
        val address = MemoryUtil.memAddress(pixels!!)
        val destination = MemoryUtil.memAddress(upload)
        for (row in 0 until bounds.height) {
            MemoryUtil.memCopy(
                address + (bounds.bottom - row - 1) * stride + bounds.left * 4L,
                destination + row * rowBytes.toLong(),
                rowBytes.toLong(),
            )
        }
        return upload
    }

    override fun close() {
        view?.close()
        texture?.close()
        surface?.close()
        pixels?.let { MemoryUtil.memFree(it) }
        uploadPixels?.let { MemoryUtil.memFree(it) }
        view = null
        texture = null
        surface = null
        pixels = null
        uploadPixels = null
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

    companion object {
        private val pipeline by lazy {
            val vanilla = RenderPipelines.ENTITY_OUTLINE_BLIT
            val builder =
                RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("moons", "pipeline/skia_overlay"))
                    .withVertexShader(vanilla.shaders[ShaderType.VERTEX]!!)
                    .withFragmentShader(vanilla.shaders[ShaderType.FRAGMENT]!!)
                    .withPrimitiveTopology(vanilla.primitiveTopology)
                    .withColorTargetState(
                        ColorTargetState(
                            Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
                            GpuFormat.RGBA8_UNORM,
                            7,
                        )
                    )
            vanilla.bindGroupLayouts.forEach { builder.withBindGroupLayout(it) }
            GameAccess.registerPipeline(builder.build())
        }
    }
}
