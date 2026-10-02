package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

final class GltfNativeState implements AutoCloseable {
    private final ShaderInstance shader = RenderSystem.getShader();
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int targetRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int targetAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int[] shaderTextures;
    private final int[] textureBindings;
    private final int bufferTextureBinding;

    GltfNativeState() {
        this(9);
    }

    GltfNativeState(int textureUnits) {
        shaderTextures = new int[textureUnits];
        textureBindings = new int[textureUnits];
        for (int unit = 0; unit < shaderTextures.length; unit++) {
            shaderTextures[unit] = RenderSystem.getShaderTexture(unit);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            textureBindings[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        bufferTextureBinding = textureUnits >= 9 ? GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER) : -1;
        GlStateManager._activeTexture(activeTexture);
    }

    @Override
    public void close() {
        for (int unit = 0; unit < shaderTextures.length; unit++) {
            RenderSystem.setShaderTexture(unit, shaderTextures[unit]);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(textureBindings[unit]);
        }
        if (bufferTextureBinding >= 0) GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, bufferTextureBinding);
        GlStateManager._activeTexture(activeTexture);
        RenderSystem.blendFuncSeparate(sourceRgb, targetRgb, sourceAlpha, targetAlpha);
        if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        RenderSystem.depthMask(depthMask);
        RenderSystem.depthFunc(depthFunction);
        RenderSystem.setShader(() -> shader);
        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, arrayBuffer);
        BufferUploader.invalidate();
    }
}
