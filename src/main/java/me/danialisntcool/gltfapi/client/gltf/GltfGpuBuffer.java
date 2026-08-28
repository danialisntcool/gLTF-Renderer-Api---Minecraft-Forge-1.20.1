package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class GltfGpuBuffer implements AutoCloseable {
    private final VertexFormat format;
    private int vertexBufferId = -1;
    private int indexBufferId = -1;
    private int arrayObjectId = -1;
    private int indexCount;

    GltfGpuBuffer(VertexFormat format, BufferBuilder.RenderedBuffer rendered, int[] indices) {
        this.format = format;
        upload(rendered, indices);
    }

    void updateIndices(int[] indices) {
        RenderSystem.assertOnRenderThreadOrInit();
        GlStateManager._glBindVertexArray(arrayObjectId);
        GlStateManager._glBindBuffer(34963, indexBufferId);
        ByteBuffer indexData = indexData(indices);
        try {
            GlStateManager._glBufferData(34963, indexData, 35048);
        } finally {
            MemoryUtil.memFree(indexData);
        }
        indexCount = indices.length;
        GlStateManager._glBindVertexArray(0);
    }

    void drawWithShader(Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        for (int unit = 0; unit < 12; unit++) {
            shader.setSampler("Sampler" + unit, RenderSystem.getShaderTexture(unit));
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
        shader.apply();
        GlStateManager._glBindVertexArray(arrayObjectId);
        GlStateManager._drawElements(4, indexCount, 5125, 0L);
        GlStateManager._glBindVertexArray(0);
        shader.clear();
    }

    boolean isInvalid() {
        return arrayObjectId < 0;
    }

    @Override
    public void close() {
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
    }

    private void upload(BufferBuilder.RenderedBuffer rendered, int[] indices) {
        RenderSystem.assertOnRenderThreadOrInit();
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
        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(34962, 0);
    }

    private ByteBuffer indexData(int[] indices) {
        ByteBuffer data = MemoryUtil.memAlloc(indices.length * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int index : indices) {
            data.putInt(index);
        }
        return data.flip();
    }
}
