package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.system.MemoryUtil;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import org.lwjgl.system.MemoryStack;

public final class GltfPersistentStreamSmoke {
    public static void verifyDraw(ShaderInstance shader, TextureTarget texture, TextureTarget canvas) throws Exception {
        var active = GltfGpuBuffer.class.getDeclaredField("persistentActive");
        active.setAccessible(true);
        var allocated = GltfPersistentStream.class.getDeclaredField("allocatedBytes");
        allocated.setAccessible(true);
        var disabled = GltfPersistentStream.class.getDeclaredField("failed");
        disabled.setAccessible(true);
        var persistent = GltfGpuBuffer.class.getDeclaredField("persistent");
        persistent.setAccessible(true);
        BufferBuilder builder = new BufferBuilder(256);
        boolean supported = GltfPersistentStream.supported();
        long baseline = GltfPersistentStream.allocatedBytes();
        try (GltfGpuBuffer buffer = new GltfGpuBuffer(DefaultVertexFormat.POSITION_TEX);
             MemoryStack stack = MemoryStack.stackPush()) {
            RenderSystem.setShaderTexture(0, texture.getColorTextureId());
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            for (int pass = 0; pass < 9; pass++) {
                long reserved = GltfPersistentStream.allocatedBytes();
                if (pass == 0) allocated.setLong(null, ModMetadata.GPU_STREAM_CACHE_BYTES);
                if (pass == 8) disabled.setBoolean(null, true);
                canvas.clear(false);
                canvas.bindWrite(true);
                builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
                builder.vertex(-0.5F, -0.5F, 0).uv(0, 0).endVertex();
                builder.vertex(0.5F, -0.5F, 0).uv(1, 0).endVertex();
                builder.vertex(0, 0.5F, 0).uv(0.5F, 1).endVertex();
                var rendered = builder.end();
                try { buffer.stream(rendered, stack.ints(99, 0, 1, 2).position(1)); }
                finally {
                    rendered.release();
                    if (pass == 0) allocated.setLong(null, reserved);
                    if (pass == 8) disabled.setBoolean(null, !supported);
                }
                if (active.getBoolean(buffer) != (supported && pass != 0 && pass != 8))
                    throw new IllegalStateException("Automatic streaming did not select the correct upload path");
                if (pass == 8 && persistent.get(buffer) != null)
                    throw new IllegalStateException("Driver fallback retained mapped geometry allocations");
                buffer.drawWithShader(new Matrix4f(), new Matrix4f(), shader);
                var pixel = stack.mallocFloat(4);
                GL11.glReadPixels(16, 7, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                if (pixel.get(1) < 0.99F || GL11.glGetError() != GL11.GL_NO_ERROR)
                    throw new IllegalStateException("Automatic streaming drew from the wrong vertex/index allocation");
            }
            System.out.println("Automatic streaming draw GPU regression passed: budget fallback, recovery, actual rendering and fenced slot reuse");
        }
        if (GltfPersistentStream.allocatedBytes() != baseline)
            throw new IllegalStateException("Automatic streaming retained mapped allocations after close");
    }

    public static void verify() {
        if (!GltfPersistentStream.supported()) {
            System.out.println("Persistent streaming unavailable on this context; standard upload fallback remains enabled");
            return;
        }
        int sentinelVao = GlStateManager._glGenVertexArrays();
        int sentinelBuffer = GlStateManager._glGenBuffers();
        long baseline = GltfPersistentStream.allocatedBytes();
        try (GltfPersistentStream stream = new GltfPersistentStream(DefaultVertexFormat.POSITION);
             MemoryStack stack = MemoryStack.stackPush()) {
            GlStateManager._glBindVertexArray(sentinelVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, sentinelBuffer);
            var vertices = stack.malloc(40);
            vertices.putInt(0x11223344).putFloat(1).putFloat(2).putFloat(3)
                    .putFloat(4).putFloat(5).putFloat(6).putFloat(7).putFloat(8).putFloat(9).flip().position(4);
            var indices = stack.ints(99, 0, 1, 2).position(1);
            for (int pass = 0; pass < 7; pass++) {
                if (!stream.upload(vertices, indices)) throw new IllegalStateException("Persistent slots did not retire");
                if (vertices.position() != 4 || indices.position() != 1
                        || GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) != sentinelVao
                        || GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != sentinelBuffer)
                    throw new IllegalStateException("Persistent upload altered source position or caller state");
                GlStateManager._glBindVertexArray(stream.vertexArray());
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, stream.vertexBuffer());
                if (GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, ARBBufferStorage.GL_BUFFER_IMMUTABLE_STORAGE) == 0
                        || GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_MAPPED) == 0)
                    throw new IllegalStateException("Automatic streaming did not keep immutable storage mapped");
                int buffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
                var copied = stack.mallocInt(3);
                GL15.glGetBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0, copied);
                if (buffer == 0 || copied.get(0) != 0 || copied.get(1) != 1 || copied.get(2) != 2)
                    throw new IllegalStateException("Persistent index upload copied from the wrong offset");
                stream.submitted();
                GL11.glFinish();
                GlStateManager._glBindVertexArray(sentinelVao);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, sentinelBuffer);
            }
            var larger = MemoryUtil.memAlloc(8196);
            try {
                larger.position(4);
                if (!stream.upload(larger, indices) || larger.position() != 4)
                    throw new IllegalStateException("Growing a consumed persistent source changed its offset");
                stream.submitted();
                GL11.glFinish();
            } finally { MemoryUtil.memFree(larger); }
            if (stream.storageBytes() <= 0 || GL11.glGetError() != GL11.GL_NO_ERROR)
                throw new IllegalStateException("Persistent streaming validation failed");
            System.out.println("Persistent streaming GPU regression passed: consumed offsets, three-slot reuse, fences and caller state");
        } finally {
            GlStateManager._glBindVertexArray(0);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GlStateManager._glDeleteVertexArrays(sentinelVao);
            GlStateManager._glDeleteBuffers(sentinelBuffer);
        }
        if (GltfPersistentStream.allocatedBytes() != baseline)
            throw new IllegalStateException("Persistent close did not release the shared memory budget");
    }
}
