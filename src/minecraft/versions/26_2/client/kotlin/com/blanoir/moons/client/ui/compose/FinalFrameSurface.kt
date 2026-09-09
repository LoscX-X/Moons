package com.blanoir.moons.client.ui.compose

import org.jetbrains.skia.*

/** Version-owned OpenGL framebuffer binding for independent Skia layers. */
internal class FinalFrameSurface : AutoCloseable {
    private var context: DirectContext? = null
    private var target: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0

    // Direct framebuffer rendering has no retained texture; bounds are a raster-backend hint.
    @Suppress("UNUSED_PARAMETER")
    fun render(
        frameWidth: Int,
        frameHeight: Int,
        contentBounds: (() -> Rect?)? = null,
        draw: (Canvas) -> Unit,
    ) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val state = FinalFrameGl.prepare(frameWidth, frameHeight)
        try {
            if (surface == null || width != frameWidth || height != frameHeight) {
                // A resize changes the framebuffer binding, not the GL context.
                // Keep Skia's compiled programs and glyph atlas alive.
                surface?.close()
                surface = null
                target?.close()
                target = null
                val nextContext = context ?: DirectContext.makeGL().also { context = it }
                val nextTarget =
                    BackendRenderTarget.makeGL(
                            frameWidth,
                            frameHeight,
                            0,
                            0,
                            0,
                            FramebufferFormat.GR_GL_RGBA8,
                        )
                        .also { target = it }
                surface =
                    Surface.makeFromBackendRenderTarget(
                        nextContext,
                        nextTarget,
                        SurfaceOrigin.BOTTOM_LEFT,
                        SurfaceColorFormat.RGBA_8888,
                        ColorSpace.sRGB,
                    )
                width = frameWidth
                height = frameHeight
            }
            context?.resetAll()
            draw(surface!!.canvas)
            surface?.flushAndSubmit()
        } finally {
            state.restore()
        }
    }

    override fun close() {
        surface?.close()
        target?.close()
        context?.close()
        surface = null
        target = null
        context = null
        width = 0
        height = 0
    }
}
