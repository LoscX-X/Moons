package com.blanoir.moons.client.ui.compose

import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL33C

/** OpenGL state isolation shared by independent Skia final-frame layers. */
internal object FinalFrameGl {
    private const val MINECRAFT_TEXTURE_UNITS = 12

    fun prepare(width: Int, height: Int): Snapshot {
        val snapshot = Snapshot.capture()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0)
        GL11C.glViewport(0, 0, width, height)
        GL11C.glDisable(GL11C.GL_DEPTH_TEST)
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST)
        GL11C.glColorMask(true, true, true, true)
        GL11C.glEnable(GL11C.GL_BLEND)
        GL14C.glBlendFuncSeparate(
            GL11C.GL_SRC_ALPHA,
            GL11C.GL_ONE_MINUS_SRC_ALPHA,
            GL11C.GL_ONE,
            GL11C.GL_ONE_MINUS_SRC_ALPHA,
        )
        GL20C.glUseProgram(0)
        GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0)
        // The host renderer may leave offsets that corrupt Skia's next glyph upload.
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, 0)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, 0)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0)
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0)
        return snapshot
    }

    internal data class Snapshot(
        val drawFramebuffer: Int,
        val readFramebuffer: Int,
        val viewport: IntArray,
        val program: Int,
        val vao: Int,
        val arrayBuffer: Int,
        val renderbuffer: Int,
        val activeTexture: Int,
        val textures: IntArray,
        val samplers: IntArray,
        val blend: Boolean,
        val blendSrcRgb: Int,
        val blendDstRgb: Int,
        val blendSrcAlpha: Int,
        val blendDstAlpha: Int,
        val blendEquationRgb: Int,
        val blendEquationAlpha: Int,
        val depth: Boolean,
        val depthMask: Boolean,
        val depthFunc: Int,
        val scissor: Boolean,
        val scissorBox: IntArray,
        val colorMask: IntArray,
        val cull: Boolean,
        val polygonOffset: Boolean,
        val polygonOffsetFactor: Float,
        val polygonOffsetUnits: Float,
        val colorLogic: Boolean,
        val colorLogicOp: Int,
        val framebufferSrgb: Boolean,
        val unpackAlignment: Int,
        val unpackBuffer: Int,
        val unpackRowLength: Int,
        val unpackSkipPixels: Int,
        val unpackSkipRows: Int,
        val unpackImageHeight: Int,
        val unpackSkipImages: Int,
    ) {
        fun restore() {
            // Restore every state Skia can mutate behind Minecraft's own GL
            // cache. Leaving even depth/color writes or the blend equation
            // changed corrupts translucent water, fire and dimension fog on
            // the following frame.
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer)
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GL20C.glUseProgram(program)
            GL30C.glBindVertexArray(vao)
            GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, arrayBuffer)
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, renderbuffer)
            for (unit in textures.indices) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit)
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[unit])
                GL33C.glBindSampler(unit, samplers[unit])
            }
            GL13C.glActiveTexture(activeTexture)
            if (blend) GL11C.glEnable(GL11C.GL_BLEND) else GL11C.glDisable(GL11C.GL_BLEND)
            GL14C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
            GL20C.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
            GL11C.glDepthMask(depthMask)
            GL11C.glDepthFunc(depthFunc)
            if (depth) GL11C.glEnable(GL11C.GL_DEPTH_TEST) else GL11C.glDisable(GL11C.GL_DEPTH_TEST)
            GL11C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST)
            else GL11C.glDisable(GL11C.GL_SCISSOR_TEST)
            GL11C.glColorMask(
                colorMask[0] != 0,
                colorMask[1] != 0,
                colorMask[2] != 0,
                colorMask[3] != 0,
            )
            if (cull) GL11C.glEnable(GL11C.GL_CULL_FACE) else GL11C.glDisable(GL11C.GL_CULL_FACE)
            GL11C.glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits)
            if (polygonOffset) GL11C.glEnable(GL11C.GL_POLYGON_OFFSET_FILL)
            else GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL)
            GL11C.glLogicOp(colorLogicOp)
            if (colorLogic) GL11C.glEnable(GL11C.GL_COLOR_LOGIC_OP)
            else GL11C.glDisable(GL11C.GL_COLOR_LOGIC_OP)
            if (framebufferSrgb) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB)
            else GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB)
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, unpackAlignment)
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, unpackRowLength)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, unpackSkipPixels)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, unpackSkipRows)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, unpackImageHeight)
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, unpackSkipImages)
        }

        companion object {
            fun capture(): Snapshot {
                val viewport = IntArray(4)
                GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport)
                val scissorBox = IntArray(4)
                GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBox)
                val colorMask = IntArray(4)
                GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, colorMask)
                val active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)
                val textures = IntArray(MINECRAFT_TEXTURE_UNITS)
                val samplers = IntArray(MINECRAFT_TEXTURE_UNITS)
                for (unit in 0 until MINECRAFT_TEXTURE_UNITS) {
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit)
                    textures[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)
                    samplers[unit] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING)
                }
                GL13C.glActiveTexture(active)
                return Snapshot(
                    GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    viewport,
                    GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                    GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                    GL11C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING),
                    active,
                    textures,
                    samplers,
                    GL11C.glIsEnabled(GL11C.GL_BLEND),
                    GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB),
                    GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),
                    GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA),
                    GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA),
                    GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),
                    GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA),
                    GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                    GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                    GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                    GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                    scissorBox,
                    colorMask,
                    GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                    GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL),
                    GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR),
                    GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS),
                    GL11C.glIsEnabled(GL11C.GL_COLOR_LOGIC_OP),
                    GL11C.glGetInteger(GL11C.GL_LOGIC_OP_MODE),
                    GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB),
                    GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT),
                    GL11C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_ROW_LENGTH),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_PIXELS),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_ROWS),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_IMAGE_HEIGHT),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_IMAGES),
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Snapshot

            if (drawFramebuffer != other.drawFramebuffer) return false
            if (readFramebuffer != other.readFramebuffer) return false
            if (program != other.program) return false
            if (vao != other.vao) return false
            if (arrayBuffer != other.arrayBuffer) return false
            if (renderbuffer != other.renderbuffer) return false
            if (activeTexture != other.activeTexture) return false
            if (blend != other.blend) return false
            if (blendSrcRgb != other.blendSrcRgb) return false
            if (blendDstRgb != other.blendDstRgb) return false
            if (blendSrcAlpha != other.blendSrcAlpha) return false
            if (blendDstAlpha != other.blendDstAlpha) return false
            if (blendEquationRgb != other.blendEquationRgb) return false
            if (blendEquationAlpha != other.blendEquationAlpha) return false
            if (depth != other.depth) return false
            if (depthMask != other.depthMask) return false
            if (depthFunc != other.depthFunc) return false
            if (scissor != other.scissor) return false
            if (cull != other.cull) return false
            if (polygonOffset != other.polygonOffset) return false
            if (polygonOffsetFactor != other.polygonOffsetFactor) return false
            if (polygonOffsetUnits != other.polygonOffsetUnits) return false
            if (colorLogic != other.colorLogic) return false
            if (colorLogicOp != other.colorLogicOp) return false
            if (framebufferSrgb != other.framebufferSrgb) return false
            if (unpackAlignment != other.unpackAlignment) return false
            if (unpackBuffer != other.unpackBuffer) return false
            if (unpackRowLength != other.unpackRowLength) return false
            if (unpackSkipPixels != other.unpackSkipPixels) return false
            if (unpackSkipRows != other.unpackSkipRows) return false
            if (unpackImageHeight != other.unpackImageHeight) return false
            if (unpackSkipImages != other.unpackSkipImages) return false
            if (!viewport.contentEquals(other.viewport)) return false
            if (!textures.contentEquals(other.textures)) return false
            if (!samplers.contentEquals(other.samplers)) return false
            if (!scissorBox.contentEquals(other.scissorBox)) return false
            if (!colorMask.contentEquals(other.colorMask)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = drawFramebuffer
            result = 31 * result + readFramebuffer
            result = 31 * result + program
            result = 31 * result + vao
            result = 31 * result + arrayBuffer
            result = 31 * result + renderbuffer
            result = 31 * result + activeTexture
            result = 31 * result + blend.hashCode()
            result = 31 * result + blendSrcRgb
            result = 31 * result + blendDstRgb
            result = 31 * result + blendSrcAlpha
            result = 31 * result + blendDstAlpha
            result = 31 * result + blendEquationRgb
            result = 31 * result + blendEquationAlpha
            result = 31 * result + depth.hashCode()
            result = 31 * result + depthMask.hashCode()
            result = 31 * result + depthFunc
            result = 31 * result + scissor.hashCode()
            result = 31 * result + cull.hashCode()
            result = 31 * result + polygonOffset.hashCode()
            result = 31 * result + polygonOffsetFactor.hashCode()
            result = 31 * result + polygonOffsetUnits.hashCode()
            result = 31 * result + colorLogic.hashCode()
            result = 31 * result + colorLogicOp
            result = 31 * result + framebufferSrgb.hashCode()
            result = 31 * result + unpackAlignment
            result = 31 * result + unpackBuffer
            result = 31 * result + unpackRowLength
            result = 31 * result + unpackSkipPixels
            result = 31 * result + unpackSkipRows
            result = 31 * result + unpackImageHeight
            result = 31 * result + unpackSkipImages
            result = 31 * result + viewport.contentHashCode()
            result = 31 * result + textures.contentHashCode()
            result = 31 * result + samplers.contentHashCode()
            result = 31 * result + scissorBox.contentHashCode()
            result = 31 * result + colorMask.contentHashCode()
            return result
        }
    }
}
