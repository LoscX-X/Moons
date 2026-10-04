package com.blanoir.moons.client.render.model;

import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/** Screen-space silhouette outline for a captured 1.8 model, preserving its original skin. */
public final class VisualModelOutline {
    private static int program;

    private VisualModelOutline() {}

    public static void apply(Framebuffer source, int color, float light, boolean fill) {
        initialize();
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source.framebufferTexture);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "image"), 0);
            GL20.glUniform2f(
                    GL20.glGetUniformLocation(program, "pixel"),
                    1f / source.framebufferWidth,
                    1f / source.framebufferHeight);
            GL20.glUniform4f(
                    GL20.glGetUniformLocation(program, "outline"),
                    (color >> 16 & 255) / 255f,
                    (color >> 8 & 255) / 255f,
                    (color & 255) / 255f,
                    (color >>> 24) / 255f);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "light"), light);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "filled"), fill ? 1 : 0);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, 0);
            GL11.glVertex2f(-1, -1);
            GL11.glTexCoord2f(1, 0);
            GL11.glVertex2f(1, -1);
            GL11.glTexCoord2f(1, 1);
            GL11.glVertex2f(1, 1);
            GL11.glTexCoord2f(0, 1);
            GL11.glVertex2f(-1, 1);
            GL11.glEnd();
        } finally {
            GL20.glUseProgram(previous);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(mode);
            GL11.glPopAttrib();
        }
    }

    private static void initialize() {
        if (program != 0) return;
        int vertex =
                compile(
                        GL20.GL_VERTEX_SHADER,
                        "#version 120\nvoid main(){gl_Position=gl_Vertex;gl_TexCoord[0]=gl_MultiTexCoord0;}");
        int fragment = 0, next = 0;
        try {
            fragment =
                    compile(
                            GL20.GL_FRAGMENT_SHADER,
                            "#version 120\nuniform sampler2D image;uniform vec2 pixel;uniform vec4 outline;uniform float light;uniform float filled;void main(){vec2 uv=gl_TexCoord[0].xy;vec4 c=texture2D(image,uv);float a=0.0;for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)a=max(a,texture2D(image,uv+vec2(float(x),float(y))*pixel).a);float edge=max(0.0,a-c.a)*outline.a;vec4 body=vec4(c.rgb*light,c.a)*filled;gl_FragColor=body+vec4(outline.rgb*edge,edge)*(1.0-body.a);}");
            next = GL20.glCreateProgram();
            GL20.glAttachShader(next, vertex);
            GL20.glAttachShader(next, fragment);
            GL20.glLinkProgram(next);
            if (GL20.glGetProgrami(next, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(next, 4096));
            program = next;
            next = 0;
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
            if (next != 0) GL20.glDeleteProgram(next);
        }
    }

    private static int compile(int type, String text) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, text);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    public static void close() {
        if (program != 0) GL20.glDeleteProgram(program);
        program = 0;
    }
}
