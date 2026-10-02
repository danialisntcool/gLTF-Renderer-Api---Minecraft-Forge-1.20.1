package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

public final class GltfInstancingSmoke {
    public static void verify(String vertexSource) {
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, """
                #version 150
                in vec4 lightMapColor;
                in vec4 overlayColor;
                out vec4 fragColor;
                void main() { fragColor = lightMapColor * overlayColor.a; }
                """);
        int program = GL20.glCreateProgram();
        int vao = GL30.glGenVertexArrays();
        int buffer = GL15.glGenBuffers();
        int indices = GL15.glGenBuffers();
        int target = GL30.glGenFramebuffers();
        int color = GL11.glGenTextures();
        int light = GL11.glGenTextures();
        int overlay = GL11.glGenTextures();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                    stack.floats(-1, -1, 0, 1, -1, 0, 0, 1, 0), GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, stack.ints(0, 1, 2), GL15.GL_STATIC_DRAW);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 64, 64, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, color, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, light);
            var pixels = stack.mallocFloat(16 * 16 * 4);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) pixels.put(x == 0 ? 0.2F : 0.8F).put(1).put(1).put(1);
            }
            pixels.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 16, 16, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 6);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, overlay);
            pixels.clear();
            for (int index = 0; index < 256; index++) pixels.put(0).put(0).put(0).put(1);
            pixels.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 16, 16, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler2"), 2);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler6"), 6);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler8"), 8);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "HasInstances"), 1);
            var identity = stack.mallocFloat(16);
            new Matrix4f().get(identity);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ModelViewMat"), false, identity);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ProjMat"), false, identity);
            float[] data = new float[64];
            float[] instance = new float[32];
            GltfNativeBatch.pack(new Matrix4f().translate(-0.5F, 0, 0).scale(0.3F), 0, 0, instance, new Matrix3f());
            System.arraycopy(instance, 0, data, 0, 32);
            GltfNativeBatch.pack(new Matrix4f().translate(0.5F, 0, 0).scale(0.3F),
                    0x00f000f0, 0x000a0000, instance, new Matrix3f());
            System.arraycopy(instance, 0, data, 32, 32);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 8);
            int texture = GltfInstanceBuffer.upload(data, data.length);
            if (texture != GltfInstanceBuffer.upload(data, data.length)) {
                throw new IllegalStateException("Instance texture was recreated during an update");
            }
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL11.glViewport(0, 0, 64, 64);
            GL11.glClearColor(0, 0, 0, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, 3, GL11.GL_UNSIGNED_INT, 0, 2);
            var pixel = stack.mallocFloat(4);
            GL11.glReadPixels(16, 32, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            float left = pixel.get(0);
            GL11.glReadPixels(48, 32, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            float right = pixel.get(0);
            GL11.glReadPixels(32, 32, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            float middle = pixel.get(0);
            if (Math.abs(left - 0.2F) > 0.001F || Math.abs(right - 0.8F) > 0.001F || middle != 0) {
                throw new IllegalStateException("Instanced poses or light are wrong: " + left + ", " + right + ", " + middle);
            }
            int error = GL11.glGetError();
            if (error != 0) throw new IllegalStateException("Instancing GL error " + error);
            System.out.println("Native instancing GPU regression passed: distinct poses/light and reused texture buffer");
        } finally {
            GltfInstanceBuffer.clear();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(target);
            GL11.glDeleteTextures(color);
            GL11.glDeleteTextures(light);
            GL11.glDeleteTextures(overlay);
            GL15.glDeleteBuffers(buffer);
            GL15.glDeleteBuffers(indices);
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
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
