package com.blanoir.moons.client.render.frame

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33

/** OpenGL state isolation shared by independent Skia final-frame layers. */
internal object FinalFrameGl {
    private const val MINECRAFT_TEXTURE_UNITS = 3

    private fun readInts(key: Int, result: IntArray) {
        val buffer = org.lwjgl.BufferUtils.createIntBuffer(16)
        GL11.glGetInteger(key, buffer)
        for (i in result.indices) result[i] = buffer.get(i)
    }

    fun prepare(width: Int, height: Int): Snapshot {
        val snapshot = Snapshot.capture()
        // Minecraft 1.8 draws through compatibility client arrays. Skia can
        // change the default VAO's pointers/enables even when its VAO binding
        // is restored, so preserve those arrays and fixed-function state too.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT or GL11.GL_CLIENT_PIXEL_STORE_BIT)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        GL11.glViewport(0, 0, width, height)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glColorMask(true, true, true, true)
        GL11.glEnable(GL11.GL_BLEND)
        GL14.glBlendFuncSeparate(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
        )
        GL20.glUseProgram(0)
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
        // The host renderer may leave offsets that corrupt Skia's next glyph upload.
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0)
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
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GL20.glUseProgram(program)
            GL30.glBindVertexArray(vao)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer)
            for (unit in textures.indices) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[unit])
                GL33.glBindSampler(unit, samplers[unit])
            }
            GL13.glActiveTexture(activeTexture)
            if (blend) GL11.glEnable(GL11.GL_BLEND) else GL11.glDisable(GL11.GL_BLEND)
            GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
            GL11.glDepthMask(depthMask)
            GL11.glDepthFunc(depthFunc)
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST) else GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST)
            else GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glColorMask(
                colorMask[0] != 0,
                colorMask[1] != 0,
                colorMask[2] != 0,
                colorMask[3] != 0,
            )
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits)
            if (polygonOffset) GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL)
            else GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL)
            GL11.glLogicOp(colorLogicOp)
            if (colorLogic) GL11.glEnable(GL11.GL_COLOR_LOGIC_OP)
            else GL11.glDisable(GL11.GL_COLOR_LOGIC_OP)
            if (framebufferSrgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB)
            else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment)
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, unpackRowLength)
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, unpackSkipPixels)
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, unpackSkipRows)
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, unpackImageHeight)
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, unpackSkipImages)
            GL11.glPopClientAttrib()
            GL11.glPopAttrib()
        }

        companion object {
            fun capture(): Snapshot {
                val viewport = IntArray(4)
                readInts(GL11.GL_VIEWPORT, viewport)
                val scissorBox = IntArray(4)
                readInts(GL11.GL_SCISSOR_BOX, scissorBox)
                val colorMask = IntArray(4)
                readInts(GL11.GL_COLOR_WRITEMASK, colorMask)
                val active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
                val textures = IntArray(MINECRAFT_TEXTURE_UNITS)
                val samplers = IntArray(MINECRAFT_TEXTURE_UNITS)
                for (unit in 0 until MINECRAFT_TEXTURE_UNITS) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
                    textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                    samplers[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING)
                }
                GL13.glActiveTexture(active)
                return Snapshot(
                    GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                    viewport,
                    GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                    GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                    GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                    GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING),
                    active,
                    textures,
                    samplers,
                    GL11.glIsEnabled(GL11.GL_BLEND),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                    GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                    GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
                    GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA),
                    GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                    GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
                    GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                    scissorBox,
                    colorMask,
                    GL11.glIsEnabled(GL11.GL_CULL_FACE),
                    GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL),
                    GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR),
                    GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS),
                    GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP),
                    GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE),
                    GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB),
                    GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),
                    GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING),
                    GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH),
                    GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS),
                    GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),
                    GL11.glGetInteger(GL12.GL_UNPACK_IMAGE_HEIGHT),
                    GL11.glGetInteger(GL12.GL_UNPACK_SKIP_IMAGES),
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
