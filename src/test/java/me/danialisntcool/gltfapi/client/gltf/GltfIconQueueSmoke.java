package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Proxy;
import java.util.Optional;

public final class GltfIconQueueSmoke {
    public static void verify() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var bootstrap = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrap.setAccessible(true);
        bootstrap.setBoolean(null, true);
        Class.forName("net.minecraft.core.registries.BuiltInRegistries");
        PackResources pack = (PackResources) Proxy.newProxyInstance(PackResources.class.getClassLoader(),
                new Class<?>[]{PackResources.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "packId" -> "glTF GPU test";
                    case "isBuiltin" -> true;
                    default -> null;
                });
        ResourceProvider resources = location -> {
            String path = "/assets/" + location.getNamespace() + "/" + location.getPath();
            return GltfIconQueueSmoke.class.getResource(path) == null ? Optional.empty()
                    : Optional.of(new Resource(pack, () -> GltfIconQueueSmoke.class.getResourceAsStream(path)));
        };
        var shaderField = GltfShaders.class.getDeclaredField("iconShader");
        shaderField.setAccessible(true);
        Object oldShader = shaderField.get(null);
        TextureTarget icon = new TextureTarget(2, 2, true, false);
        TextureTarget canvas = new TextureTarget(32, 16, true, false);
        try (ShaderInstance shader = new ShaderInstance(resources,
                ResourceLocation.fromNamespaceAndPath("gltf_renderer_api", "gltf_icon"), DefaultVertexFormat.POSITION_TEX);
             MemoryStack stack = MemoryStack.stackPush()) {
            shaderField.set(null, shader);
            icon.setClearColor(0, 1, 0, 1);
            RenderSystem.depthMask(true);
            icon.clear(false);
            canvas.setClearColor(0, 0, 0, 0);
            canvas.clear(false);
            canvas.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableDepthTest();
            RenderType normalItem = new RenderType("gltf_inventory_order_test", DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS, 256, false, false, () -> {
                RenderSystem.setShader(() -> shader);
                RenderSystem.setShaderTexture(0, icon.getColorTextureId());
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
            }, () -> {}) {
                @Override
                public void end(BufferBuilder builder, VertexSorting sorting) {
                    var rendered = builder.end();
                    setupRenderState();
                    try { BufferUploader.drawWithShader(rendered); }
                    finally { clearRenderState(); }
                }
            };
            var buffers = MultiBufferSource.immediate(new BufferBuilder(256));
            var queued = buffers.getBuffer(normalItem);
            queued.vertex(-1, -1, 0).uv(0, 0).endVertex();
            queued.vertex(0, -1, 0).uv(1, 0).endVertex();
            queued.vertex(0, 1, 0).uv(1, 1).endVertex();
            queued.vertex(-1, 1, 0).uv(0, 1).endVertex();
            var draw = GltfGuiIconCache.class.getDeclaredMethod("draw", TextureTarget.class,
                    GltfGuiIconCache.Rectangle.class, MultiBufferSource.class);
            draw.setAccessible(true);
            draw.invoke(null, icon, new GltfGuiIconCache.Rectangle(0, 1, -1, 1, -0.5F), buffers);
            var pixel = stack.mallocFloat(4);
            GL11.glReadPixels(8, 8, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            if (pixel.get(1) < 0.99F) throw new IllegalStateException("Cached icon did not flush the earlier normal item");
            GL11.glReadPixels(24, 8, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            if (pixel.get(1) != 0) throw new IllegalStateException("Cached icon bypassed the GUI queue");
            buffers.endBatch();
            GL11.glReadPixels(24, 8, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            var depth = stack.mallocFloat(1);
            GL11.glReadPixels(24, 8, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            if (pixel.get(1) < 0.99F || depth.get(0) != 1 || !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    || !GL11.glIsEnabled(GL11.GL_DEPTH_TEST) || GL11.glGetError() != GL11.GL_NO_ERROR)
                throw new IllegalStateException("Cached icon corrupted inventory depth or caller state");
            GL11.glReadPixels(8, 8, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            if (pixel.get(1) < 0.99F) throw new IllegalStateException("Cached icon hid the neighboring normal item");
            System.out.println("Inventory queue GPU regression passed: neighboring normal item, deferred composition and depth/state isolation");
            verifyRetirement(canvas, icon, stack);
            GltfCachedGeometrySmoke.verify(resources, icon, canvas);
            GltfShaderStreamSmoke.verify(resources, icon, canvas);
            GltfPersistentStreamSmoke.verifyDraw(shader, icon, canvas);
            GltfShaderMaterialSmoke.verify();
        } finally {
            shaderField.set(null, oldShader);
            GltfGuiIconCache.clear();
            icon.destroyBuffers();
            canvas.destroyBuffers();
            RenderSystem.setShader(() -> null);
        }
    }

    private static void verifyRetirement(TextureTarget canvas, TextureTarget icon, MemoryStack stack) throws Exception {
        var retiredField = GltfGuiIconCache.class.getDeclaredField("RETIRED");
        retiredField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var retired = (java.util.List<TextureTarget>) retiredField.get(null);
        TextureTarget temporary = new TextureTarget(8, 8, true, false);
        retired.add(temporary);
        canvas.bindWrite(true);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, icon.frameBufferId);
        GlStateManager._bindTexture(icon.getColorTextureId());
        GltfGuiIconCache.beginFrame();
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != canvas.frameBufferId
                || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != icon.frameBufferId
                || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != icon.getColorTextureId())
            throw new IllegalStateException("Retiring inventory icons detached the world framebuffer or caller texture");
        GlStateManager._clearColor(0.25F, 0.5F, 0.75F, 1);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, false);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, canvas.frameBufferId);
        var pixel = stack.mallocFloat(4);
        GL11.glReadPixels(16, 8, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
        if (Math.abs(pixel.get(1) - 0.5F) > 0.01F || GL11.glGetError() != GL11.GL_NO_ERROR)
            throw new IllegalStateException("World rendering no longer reached the scene after icon retirement");
        var cacheField = GltfGuiIconCache.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (java.util.Map<Object, TextureTarget>) cacheField.get(null);
        cache.put(null, new TextureTarget(8, 8, true, false));
        canvas.bindWrite(true);
        GlStateManager._bindTexture(icon.getColorTextureId());
        GltfGuiIconCache.clear();
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != canvas.frameBufferId
                || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != canvas.frameBufferId
                || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != icon.getColorTextureId()
                || GL11.glGetError() != GL11.GL_NO_ERROR)
            throw new IllegalStateException("Clearing inventory icons detached the world framebuffer or caller texture");
        System.out.println("Inventory retirement GPU regression passed: split framebuffers, caller texture and subsequent background rendering");
    }
}
