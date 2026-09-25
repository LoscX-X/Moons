package com.blanoir.moons.client.render

import com.blanoir.moons.client.ui.compose.ComposeRenderBridge
import com.blanoir.moons.client.ui.compose.FinalFrameGl
import com.mojang.blaze3d.opengl.GlTexture
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL33C

/** Composites owned world pixels after the game blit, without touching its screenshot target. */
object VisualPresentation {
    private var program = 0
    private var vao = 0
    private var sampler = 0

    @JvmStatic
    fun render() {
        try {
            val client = Minecraft.getInstance()
            val view = VisualRenderTargets.worldOverlayView(client)
            val texture = view?.texture() as? GlTexture
            if (texture != null) {
                val state = FinalFrameGl.prepare(client.window.width, client.window.height)
                try {
                    initialize()
                    GL11C.glDisable(GL11C.GL_CULL_FACE)
                    GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD)
                    GL14C.glBlendFuncSeparate(
                        GL11C.GL_ONE,
                        GL11C.GL_ONE_MINUS_SRC_ALPHA,
                        GL11C.GL_ONE,
                        GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    )
                    GL20C.glUseProgram(program)
                    GL30C.glBindVertexArray(vao)
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0)
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture.glId())
                    GL33C.glBindSampler(0, sampler)
                    GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3)
                } finally {
                    state.restore()
                }
            }
        } finally {
            ComposeRenderBridge.renderCurrentScreen()
        }
    }

    private fun initialize() {
        if (program != 0) return
        val vertex =
            compile(
                GL20C.GL_VERTEX_SHADER,
                """
                #version 150
                out vec2 uv;
                void main() {
                    uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                }
                """
                    .trimIndent(),
            )
        var fragment = 0
        var linked = 0
        try {
            fragment =
                compile(
                    GL20C.GL_FRAGMENT_SHADER,
                    """
                    #version 150
                    uniform sampler2D Overlay;
                    in vec2 uv;
                    out vec4 color;
                    void main() { color = texture(Overlay, uv); }
                    """
                        .trimIndent(),
                )
            linked = GL20C.glCreateProgram()
            GL20C.glAttachShader(linked, vertex)
            GL20C.glAttachShader(linked, fragment)
            GL20C.glLinkProgram(linked)
            check(GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) != 0) {
                GL20C.glGetProgramInfoLog(linked)
            }
            GL20C.glUseProgram(linked)
            GL20C.glUniform1i(GL20C.glGetUniformLocation(linked, "Overlay"), 0)
            vao = GL30C.glGenVertexArrays()
            sampler = GL33C.glGenSamplers()
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST)
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST)
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE)
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE)
            program = linked
            linked = 0
        } finally {
            GL20C.glDeleteShader(vertex)
            if (fragment != 0) GL20C.glDeleteShader(fragment)
            if (linked != 0) GL20C.glDeleteProgram(linked)
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL20C.glCreateShader(type)
        GL20C.glShaderSource(shader, source)
        GL20C.glCompileShader(shader)
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            val error = GL20C.glGetShaderInfoLog(shader)
            GL20C.glDeleteShader(shader)
            error(error)
        }
        return shader
    }

    @JvmStatic
    fun close() {
        if (program != 0) GL20C.glDeleteProgram(program)
        if (vao != 0) GL30C.glDeleteVertexArrays(vao)
        if (sampler != 0) GL33C.glDeleteSamplers(sampler)
        program = 0
        vao = 0
        sampler = 0
    }
}
