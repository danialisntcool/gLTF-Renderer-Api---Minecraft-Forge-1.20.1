package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
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
    private static GltfPersistentStream persistent;
    private static boolean persistentActive;

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
        RenderSystem.assertOnRenderThreadOrInit();
        java.util.Objects.checkFromIndexSize(offset, size, values.length);
        if ((long) size * Float.BYTES > me.danialisntcool.gltfapi.generated.ModMetadata.STAGING_BYTES)
            throw new IllegalArgumentException("Instance upload exceeds the staging buffer limit");
        if (staging == null || staging.capacity() < size) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAllocFloat(Math.max(size, 4096));
        }
        staging.clear().put(values, offset, size).flip();
        int previousBuffer = GL11.glGetInteger(GL31.GL_TEXTURE_BUFFER);
        int previousTexture = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        if (texture == 0) texture = GL11.glGenTextures();
        persistentActive = false;
        try {
            if (GltfPersistentStream.supported()) {
                try {
                    if (persistent == null) persistent = GltfPersistentStream.forInstances();
                    persistentActive = persistent.upload(
                            MemoryUtil.memByteBuffer(MemoryUtil.memAddress(staging), size * Float.BYTES), null);
                } catch (RuntimeException | LinkageError exception) {
                    failPersistent(exception);
                }
            } else if (persistent != null) {
                persistent.close();
                persistent = null;
            }
            int selected;
            if (persistentActive) {
                selected = persistent.vertexBuffer();
            } else {
                if (buffer == 0) buffer = GlStateManager._glGenBuffers();
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
                GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, staging, GL15.GL_STREAM_DRAW);
                GltfRenderMetrics.upload((long) size * Float.BYTES);
                selected = buffer;
            }
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, selected);
        } finally {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, previousBuffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTexture);
        }
        return texture;
    }

    static void submitted(int selectedTexture) {
        if (!persistentActive || selectedTexture != texture) return;
        try { persistent.submitted(); }
        catch (RuntimeException | LinkageError exception) { failPersistent(exception); }
    }

    private static void failPersistent(Throwable exception) {
        persistentActive = false;
        if (persistent != null) { persistent.close(); persistent = null; }
        GltfPersistentStream.disable(exception);
    }

    static void clear() {
        RenderSystem.assertOnRenderThreadOrInit();
        if (persistent != null) { persistent.close(); persistent = null; }
        persistentActive = false;
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (staging != null) MemoryUtil.memFree(staging);
        buffer = 0;
        texture = 0;
        staging = null;
        limit = 0;
    }
}
