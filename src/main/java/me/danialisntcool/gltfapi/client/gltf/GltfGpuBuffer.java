package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL13;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class GltfGpuBuffer implements AutoCloseable {
    private static final String[] SAMPLERS = {
            "Sampler0", "Sampler1", "Sampler2", "Sampler3", "Sampler4", "Sampler5",
            "Sampler6", "Sampler7", "Sampler8", "Sampler9", "Sampler10", "Sampler11"
    };
    private final VertexFormat format;
    private int vertexBufferId = -1;
    private int indexBufferId = -1;
    private int arrayObjectId = -1;
    private int indexCount;
    private long storageBytes;
    private GltfPersistentStream persistent;
    private boolean persistentActive;

    GltfGpuBuffer(VertexFormat format, BufferBuilder.RenderedBuffer rendered, int[] indices) {
        this.format = format;
        upload(rendered, indices);
    }

    GltfGpuBuffer(VertexFormat format) {
        this.format = format;
    }

    long storageBytes() {
        return storageBytes + (persistent == null ? 0 : persistent.storageBytes());
    }

    VertexFormat format() {
        return format;
    }

    void stream(BufferBuilder.RenderedBuffer rendered, java.nio.IntBuffer indices) {
        RenderSystem.assertOnRenderThreadOrInit();
        persistentActive = false;
        if (GltfPersistentStream.supported()) {
            try {
                if (persistent == null) persistent = new GltfPersistentStream(format);
                if (persistent.upload(rendered.vertexBuffer(), indices)) {
                    persistentActive = true;
                    indexCount = indices.remaining();
                    return;
                }
            } catch (RuntimeException | LinkageError exception) {
                failPersistent(exception);
            }
        } else if (persistent != null) { persistent.close(); persistent = null; }
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            if (isInvalid()) {
                arrayObjectId = GlStateManager._glGenVertexArrays();
                vertexBufferId = GlStateManager._glGenBuffers();
                indexBufferId = GlStateManager._glGenBuffers();
            } else {
                GltfRenderMetrics.reuse();
            }
            GlStateManager._glBindVertexArray(arrayObjectId);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBufferId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, rendered.vertexBuffer(), GL15.GL_STREAM_DRAW);
            format.setupBufferState();
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STREAM_DRAW);
            indexCount = indices.remaining();
            storageBytes = rendered.vertexBuffer().remaining() + (long) indexCount * Integer.BYTES;
            GltfRenderMetrics.upload(storageBytes);
        } finally {
            GlStateManager._glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
            BufferUploader.invalidate();
        }
    }

    void updateIndices(int[] indices) {
        RenderSystem.assertOnRenderThreadOrInit();
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GlStateManager._glBindVertexArray(arrayObjectId);
        GlStateManager._glBindBuffer(34963, indexBufferId);
        ByteBuffer indexData = indexData(indices);
        try {
            GlStateManager._glBufferData(34963, indexData, 35048);
        } finally {
            MemoryUtil.memFree(indexData);
            GlStateManager._glBindVertexArray(previousVao);
            BufferUploader.invalidate();
        }
        indexCount = indices.length;
    }

    void drawWithShader(Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        drawWithShader(modelView, projection, shader, 1, 0);
    }

    void drawWithShader(Matrix4f modelView, Matrix4f projection, ShaderInstance shader,
                        int instances, int instanceTexture) {
        for (int unit = 0; unit < SAMPLERS.length; unit++) {
            shader.setSampler(SAMPLERS[unit], RenderSystem.getShaderTexture(unit));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelView);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projection);
        }
        if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
            shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.GLINT_ALPHA != null) {
            shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shader.TEXTURE_MATRIX != null) {
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shader.GAME_TIME != null) {
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }
        if (shader.SCREEN_SIZE != null) {
            shader.SCREEN_SIZE.set(Minecraft.getInstance().getWindow().getWidth(),
                    Minecraft.getInstance().getWindow().getHeight());
        }
        RenderSystem.setupShaderLights(shader);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int timing = GltfGpuTiming.begin();
        try {
            shader.apply();
            GlStateManager._glBindVertexArray(persistentActive ? persistent.vertexArray() : arrayObjectId);
            if (instanceTexture != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 8);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, instanceTexture);
                GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0L, instances);
            } else {
                GlStateManager._drawElements(4, indexCount, 5125, 0L);
            }
            GltfRenderMetrics.draw(instances);
        } finally {
            try {
                if (persistentActive) {
                    try { persistent.submitted(); }
                    catch (RuntimeException | LinkageError exception) { failPersistent(exception); }
                }
                if (instanceTexture != 0) GltfInstanceBuffer.submitted(instanceTexture);
            } finally {
                GltfGpuTiming.end(timing);
                shader.clear();
                GlStateManager._glBindVertexArray(previousVao);
                BufferUploader.invalidate();
            }
        }
    }

    boolean isInvalid() {
        return arrayObjectId < 0 && !persistentActive;
    }

    private void failPersistent(Throwable exception) {
        persistentActive = false;
        if (persistent != null) { persistent.close(); persistent = null; }
        GltfPersistentStream.disable(exception);
    }

    @Override
    public void close() {
        if (persistent != null) { persistent.close(); persistent = null; }
        persistentActive = false;
        if (vertexBufferId >= 0) {
            GlStateManager._glDeleteBuffers(vertexBufferId);
            vertexBufferId = -1;
        }
        if (indexBufferId >= 0) {
            GlStateManager._glDeleteBuffers(indexBufferId);
            indexBufferId = -1;
        }
        if (arrayObjectId >= 0) {
            GlStateManager._glDeleteVertexArrays(arrayObjectId);
            arrayObjectId = -1;
        }
        storageBytes = 0;
        indexCount = 0;
    }

    private void upload(BufferBuilder.RenderedBuffer rendered, int[] indices) {
        RenderSystem.assertOnRenderThreadOrInit();
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            arrayObjectId = GlStateManager._glGenVertexArrays();
            vertexBufferId = GlStateManager._glGenBuffers();
            indexBufferId = GlStateManager._glGenBuffers();
            GlStateManager._glBindVertexArray(arrayObjectId);
            GlStateManager._glBindBuffer(34962, vertexBufferId);
            GlStateManager._glBufferData(34962, rendered.vertexBuffer(), 35044);
            format.setupBufferState();
            GlStateManager._glBindBuffer(34963, indexBufferId);
            ByteBuffer indexData = indexData(indices);
            try {
                GlStateManager._glBufferData(34963, indexData, 35044);
            } finally {
                MemoryUtil.memFree(indexData);
            }
            indexCount = indices.length;
            storageBytes = rendered.vertexBuffer().remaining() + (long) indexCount * Integer.BYTES;
            GltfRenderMetrics.upload(storageBytes);
        } finally {
            GlStateManager._glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(34962, previousBuffer);
            BufferUploader.invalidate();
        }
    }

    private ByteBuffer indexData(int[] indices) {
        ByteBuffer data = MemoryUtil.memAlloc(indices.length * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int index : indices) {
            data.putInt(index);
        }
        return data.flip();
    }
}
