package me.danialisntcool.gltfapi.client.gltf;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

final class GltfInstanceBuffer {
    private static int buffer;
    private static int texture;
    private static FloatBuffer staging;
    private static int limit;

    private GltfInstanceBuffer() {
    }

    static int upload(float[] values, int size) {
        return upload(values, 0, size);
    }

    static int maxInstances() {
        if (limit == 0) limit = Math.max(1, GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE) / 8);
        return limit;
    }

    static int upload(float[] values, int offset, int size) {
        if (staging == null || staging.capacity() < size) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAllocFloat(Math.max(size, 4096));
        }
        staging.clear().put(values, offset, size).flip();
        int previousBuffer = GL11.glGetInteger(GL31.GL_TEXTURE_BUFFER);
        int previousTexture = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        if (buffer == 0) {
            buffer = GL15.glGenBuffers();
            texture = GL11.glGenTextures();
        }
        try {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, staging, GL15.GL_STREAM_DRAW);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
            GltfRenderMetrics.upload((long) size * Float.BYTES);
        } finally {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, previousBuffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTexture);
        }
        return texture;
    }

    static void clear() {
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (staging != null) MemoryUtil.memFree(staging);
        buffer = 0;
        texture = 0;
        staging = null;
        limit = 0;
    }
}
