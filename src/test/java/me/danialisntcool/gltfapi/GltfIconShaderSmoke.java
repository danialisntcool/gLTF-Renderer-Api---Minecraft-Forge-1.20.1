package me.danialisntcool.gltfapi;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

final class GltfIconShaderSmoke {
    static void verify(String vertexSource, String fragmentSource) {
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GL20.glCreateProgram();
        int vao = GL30.glGenVertexArrays();
        int buffer = GL15.glGenBuffers();
        int framebuffer = GL30.glGenFramebuffers();
        int color = GL11.glGenTextures();
        int depth = GL11.glGenTextures();
        int icon = GL11.glGenTextures();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glBindAttribLocation(program, 1, "UV0");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, stack.floats(
                    -1, -1, 0, 0, 0, 1, -1, 0, 1, 0, 1, 1, 0, 1, 1,
                    -1, -1, 0, 0, 0, 1, 1, 0, 1, 1, -1, 1, 0, 0, 1), GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 2, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, 2, 1, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Icon test target incomplete");
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, icon);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 2, 1, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, stack.floats(1, 0, 0, 0, 0, 1, 0, 1));
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL20.glUseProgram(program);
            var identity = stack.floats(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ModelViewMat"), false, identity);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ProjMat"), false, identity);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler0"), 0);
            GL11.glViewport(0, 0, 2, 1);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glClearColor(0.25F, 0.25F, 0.25F, 1);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            var colors = stack.mallocFloat(8);
            var depths = stack.mallocFloat(2);
            GL11.glReadPixels(0, 0, 2, 1, GL11.GL_RGBA, GL11.GL_FLOAT, colors);
            GL11.glReadPixels(0, 0, 2, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
            if (Math.abs(colors.get(0) - 0.25F) > 0.001F || depths.get(0) != 1
                    || colors.get(5) < 0.99F || Math.abs(depths.get(1) - 0.5F) > 0.001F
                    || GL11.glGetError() != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("Icon alpha/depth regression failed");
            }
            System.out.println("Icon GPU regression passed: transparent pixels preserve depth and opaque pixels render");
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL30.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(color);
            GL11.glDeleteTextures(depth);
            GL11.glDeleteTextures(icon);
            GL15.glDeleteBuffers(buffer);
            GL30.glDeleteVertexArrays(vao);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vertex);
            GL20.glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        return shader;
    }
}
