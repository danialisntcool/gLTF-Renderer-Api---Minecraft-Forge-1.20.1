package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

final class GltfPersistentStream implements AutoCloseable {
    private static GLCapabilities context;
    private static boolean failed;
    private static long allocatedBytes;
    private final Slot[] slots = new Slot[3];
    private final VertexFormat format;
    private int cursor;
    private Slot active;

    GltfPersistentStream(VertexFormat format) {
        this.format = format;
    }

    static GltfPersistentStream forInstances() {
        return new GltfPersistentStream(null);
    }

    static boolean supported() {
        var capabilities = GL.getCapabilities();
        if (context != capabilities) {
            context = capabilities;
            failed = false;
        }
        return !failed && (capabilities.OpenGL44 || capabilities.GL_ARB_buffer_storage)
                && capabilities.glBufferStorage != 0 && capabilities.glMapBufferRange != 0
                && capabilities.glFenceSync != 0 && capabilities.glClientWaitSync != 0;
    }

    static void disable(Throwable exception) {
        if (!failed) {
            failed = true;
            com.mojang.logging.LogUtils.getLogger().warn("glTF persistent streaming failed; using standard uploads | Support: {}",
                    ModMetadata.SUPPORT_URL, exception);
        }
    }

    static long allocatedBytes() {
        return allocatedBytes;
    }

    static boolean fitsBudget(long used, long required, long limit) {
        return used >= 0 && required > 0 && used <= limit && required <= limit - used;
    }

    boolean upload(ByteBuffer vertices, IntBuffer indices) {
        RenderSystem.assertOnRenderThreadOrInit();
        active = null;
        if (!supported() || vertices.remaining() == 0 || format != null && (indices == null || !indices.hasRemaining()))
            return false;
        if (!vertices.isDirect() || indices != null && !indices.isDirect())
            throw new IllegalArgumentException("Persistent uploads require direct buffers");
        long indexBytes = indices == null ? 0 : (long) indices.remaining() * Integer.BYTES;
        long required = (long) vertices.remaining() + indexBytes;
        if (required > ModMetadata.STAGING_BYTES) return false;
        int vertexCapacity = capacity(vertices.remaining());
        int indexCapacity = indexBytes == 0 ? 0 : capacity((int) indexBytes);
        long slotBytes = (long) vertexCapacity + indexCapacity;
        if (slotBytes > ModMetadata.GPU_STREAM_CACHE_BYTES / slots.length) return false;
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            for (int attempt = 0; attempt < slots.length; attempt++) {
                int index = (cursor + attempt) % slots.length;
                Slot slot = slots[index];
                if (slot != null && !slot.ready()) continue;
                boolean reused = slot != null;
                if (slot == null || slot.vertexCapacity < vertices.remaining() || slot.indexCapacity < indexBytes) {
                    if (slot != null) { slot.close(); slots[index] = null; }
                    if (!fitsBudget(allocatedBytes, slotBytes, ModMetadata.GPU_STREAM_CACHE_BYTES)) continue;
                    slot = new Slot(vertexCapacity, indexCapacity);
                    slots[index] = slot;
                    reused = false;
                }
                MemoryUtil.memCopy(MemoryUtil.memAddress(vertices), MemoryUtil.memAddress(slot.vertices), vertices.remaining());
                if (indexBytes != 0)
                    MemoryUtil.memCopy(MemoryUtil.memAddress(indices), MemoryUtil.memAddress(slot.indices), indexBytes);
                active = slot;
                cursor = (index + 1) % slots.length;
                GltfRenderMetrics.upload(required);
                if (reused) GltfRenderMetrics.reuse();
                return true;
            }
            return false;
        } finally {
            GlStateManager._glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    static int capacity(int bytes) {
        if (bytes <= 0 || bytes > 1 << 29) throw new IllegalArgumentException("Invalid persistent buffer size");
        return Math.max(4096, Integer.highestOneBit(bytes - 1) << 1);
    }

    int vertexArray() {
        if (active == null || format == null) throw new IllegalStateException("No persistent geometry upload is active");
        return active.vao;
    }

    int vertexBuffer() {
        if (active == null) throw new IllegalStateException("No persistent upload is active");
        return active.vertexBuffer;
    }

    void submitted() {
        RenderSystem.assertOnRenderThreadOrInit();
        if (active == null) throw new IllegalStateException("No persistent upload is active");
        long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (fence == 0) throw new IllegalStateException("Could not protect persistent GPU upload");
        if (active.fence != 0) GL32.glDeleteSync(active.fence);
        active.fence = fence;
    }

    long storageBytes() {
        long bytes = 0;
        for (Slot slot : slots) if (slot != null) bytes += (long) slot.vertexCapacity + slot.indexCapacity;
        return bytes;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThreadOrInit();
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] != null) slots[index].close();
            slots[index] = null;
        }
        active = null;
    }

    private final class Slot implements AutoCloseable {
        private final int vertexCapacity;
        private final int indexCapacity;
        private int vao;
        private int vertexBuffer;
        private int indexBuffer;
        private ByteBuffer vertices;
        private ByteBuffer indices;
        private long fence;
        private boolean reserved;

        private Slot(int vertexCapacity, int indexCapacity) {
            this.vertexCapacity = vertexCapacity;
            this.indexCapacity = indexCapacity;
            allocatedBytes += (long) vertexCapacity + indexCapacity;
            reserved = true;
            try {
                if (format != null) vao = GlStateManager._glGenVertexArrays();
                vertexBuffer = GlStateManager._glGenBuffers();
                if (indexCapacity != 0) indexBuffer = GlStateManager._glGenBuffers();
                if (vao != 0) GlStateManager._glBindVertexArray(vao);
                vertices = allocate(GL15.GL_ARRAY_BUFFER, vertexBuffer, vertexCapacity);
                if (format != null) format.setupBufferState();
                if (indexBuffer != 0) indices = allocate(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, indexCapacity);
            } catch (RuntimeException | LinkageError exception) {
                close();
                throw exception;
            }
        }

        private ByteBuffer allocate(int target, int buffer, int size) {
            GlStateManager._glBindBuffer(target, buffer);
            int flags = GL30.GL_MAP_WRITE_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT | ARBBufferStorage.GL_MAP_COHERENT_BIT;
            ARBBufferStorage.glBufferStorage(target, size, flags);
            if (GL15.glGetBufferParameteri(target, GL15.GL_BUFFER_SIZE) != size)
                throw new IllegalStateException("Persistent buffer storage allocation failed");
            ByteBuffer result = GL30.glMapBufferRange(target, 0, size, flags);
            if (result == null) throw new IllegalStateException("Persistent mapping unavailable");
            return result.clear();
        }

        private boolean ready() {
            if (fence == 0) return true;
            int result = GL32.glClientWaitSync(fence, 0, 0);
            if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
                GL32.glDeleteSync(fence);
                fence = 0;
                return true;
            }
            if (result == GL32.GL_WAIT_FAILED) throw new IllegalStateException("GPU upload fence failed");
            return false;
        }

        @Override
        public void close() {
            if (fence != 0) { GL32.glDeleteSync(fence); fence = 0; }
            if (vertexBuffer != 0) { GlStateManager._glDeleteBuffers(vertexBuffer); vertexBuffer = 0; }
            if (indexBuffer != 0) { GlStateManager._glDeleteBuffers(indexBuffer); indexBuffer = 0; }
            if (vao != 0) { GlStateManager._glDeleteVertexArrays(vao); vao = 0; }
            vertices = null;
            indices = null;
            if (reserved) {
                allocatedBytes -= (long) vertexCapacity + indexCapacity;
                reserved = false;
            }
        }
    }
}
