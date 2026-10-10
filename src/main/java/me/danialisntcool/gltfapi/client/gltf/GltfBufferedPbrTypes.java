package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import me.danialisntcool.gltfapi.client.render.GltfVertexFormats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class GltfBufferedPbrTypes {
    private static final Map<GltfMaterial, RenderType> TYPES = new ConcurrentHashMap<>();

    private GltfBufferedPbrTypes() {
    }

    static RenderType get(GltfMaterial material) {
        return TYPES.computeIfAbsent(material, GltfBufferedPbrTypes::create);
    }

    static void clear() {
        TYPES.clear();
    }

    private static RenderType create(GltfMaterial material) {
        Bindings bindings = new Bindings(material);
        return new RenderType("gltf_buffered_pbr", GltfVertexFormats.BUFFERED_PBR,
                VertexFormat.Mode.TRIANGLES, 1536, true, false, bindings::setup, bindings::clear) {
            @Override
            public void end(com.mojang.blaze3d.vertex.BufferBuilder builder,
                            com.mojang.blaze3d.vertex.VertexSorting sorting) {
                if (!builder.building()) return;
                int timing = GltfGpuTiming.begin();
                try {
                    var rendered = builder.end();
                    setupRenderState();
                    try {
                        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(rendered);
                        GltfRenderMetrics.draw(1);
                    } finally {
                        clearRenderState();
                    }
                } finally {
                    GltfGpuTiming.end(timing);
                }
            }
        };
    }

    private static final class Bindings {
        private final GltfMaterial material;
        private final ArrayDeque<GltfNativeState> states = new ArrayDeque<>();

        private Bindings(GltfMaterial material) {
            this.material = material;
        }

        private void setup() {
            GltfNativeState state = new GltfNativeState();
            states.push(state);
            try {
                Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
                Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor();
                RenderSystem.setShaderTexture(6, RenderSystem.getShaderTexture(1));
                RenderSystem.setShader(GltfShaders::bufferedModelShader);
                GltfRenderer.bindMaterialTextures(material);
                GltfRenderer.applyMaterialUniforms(GltfShaders.bufferedModelShader(), material);
                RenderSystem.enableDepthTest();
                if (material.doubleSided()) RenderSystem.disableCull(); else RenderSystem.enableCull();
                if (material.alphaMode() == GltfMaterial.AlphaMode.BLEND) {
                    RenderSystem.enableBlend();
                    if (GltfRenderer.isOrthographic(RenderSystem.getProjectionMatrix()))
                        RenderSystem.blendFuncSeparate(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
                                org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
                    else RenderSystem.defaultBlendFunc();
                    RenderSystem.depthMask(false);
                } else {
                    RenderSystem.disableBlend();
                    RenderSystem.depthMask(true);
                }
            } catch (RuntimeException | LinkageError exception) {
                clear();
                throw exception;
            }
        }

        private void clear() {
            states.pop().close();
        }
    }
}
