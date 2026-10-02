package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public final class GltfBufferPoolSmoke {
    public static void verify() {
        int sentinelVao = GL30.glGenVertexArrays();
        int sentinelBuffer = GL15.glGenBuffers();
        GltfGpuBuffer buffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL30.glBindVertexArray(sentinelVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, sentinelBuffer);
            BufferBuilder builder = new BufferBuilder(256);
            buffer = GltfGpuBufferPool.acquire(DefaultVertexFormat.NEW_ENTITY);
            for (int pass = 0; pass < 2; pass++) {
                builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                for (int vertex = 0; vertex < 3; vertex++) {
                    builder.vertex(vertex, 0, 0).color(255, 255, 255, 255).uv(0, 0)
                            .overlayCoords(0).uv2(240).normal(0, 1, 0).endVertex();
                }
                BufferBuilder.RenderedBuffer rendered = builder.end();
                try {
                    buffer.stream(rendered, stack.ints(0, 1, 2));
                } finally {
                    rendered.release();
                }
                if (GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) != sentinelVao
                        || GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != sentinelBuffer) {
                    throw new IllegalStateException("Compatibility upload changed caller bindings");
                }
                GltfGpuBufferPool.release(DefaultVertexFormat.NEW_ENTITY, buffer);
                GltfGpuBuffer acquired = GltfGpuBufferPool.acquire(DefaultVertexFormat.NEW_ENTITY);
                if (acquired != buffer) throw new IllegalStateException("Compatibility GPU buffer was not reused");
                buffer = acquired;
            }
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("Buffer reuse test GL error");
            System.out.println("Compatibility GPU regression passed: reused buffers and restored caller bindings");
        } finally {
            if (buffer != null) buffer.close();
            GltfGpuBufferPool.clear();
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL30.glDeleteVertexArrays(sentinelVao);
            GL15.glDeleteBuffers(sentinelBuffer);
        }
    }
}
