package com.blanoir.moons.client.ui.compose

import com.blanoir.moons.client.render.VisualPresentation
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView

/** 26.3/26.4 RenderPearl binding for OpenGL and Vulkan presentation. */
internal class GameOverlayBackend : OverlayBackend<RenderTarget> {
    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null

    override val deviceIdentity: Any
        get() = RenderSystem.getDevice()

    override val ready: Boolean
        get() = texture != null && view != null

    override fun outputTarget(width: Int, height: Int): RenderTarget? {
        val target = VisualPresentation.outputTarget() ?: return null
        return target.takeIf { it.width == width && it.height == height }
    }

    override fun create(width: Int, height: Int) {
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

    override fun upload(surface: RasterFrameBuffer, regions: List<RasterRegion>, height: Int) {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        for (region in regions) {
            encoder.writeToTexture(
                texture!!,
                surface.pack(region.left, region.top, region.width, region.height),
                0,
                0,
                region.left,
                height - region.bottom,
                region.width,
                region.height,
            )
        }
    }

    override fun blend(target: RenderTarget, region: RasterRegion, height: Int) {
        VisualPresentation.blend(
            view!!,
            target,
            region.left,
            height - region.bottom,
            region.width,
            region.height,
        )
    }

    override fun close() {
        view?.close()
        texture?.close()
    }

    override fun reset() {
        view = null
        texture = null
    }
}
