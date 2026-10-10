package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.util.List;

public final class GltfCachedGeometrySmoke {
    public static void verify(ResourceProvider resources, TextureTarget texture, TextureTarget canvas) throws Exception {
        ModList mods = ModList.of(List.of(), List.of());
        var loadedMods = ModList.class.getDeclaredMethod("setLoadedMods", List.class);
        loadedMods.setAccessible(true);
        loadedMods.invoke(mods, List.of());
        GltfMaterial material = new GltfMaterial(null, null, null, null, null,
                1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, GltfMaterial.AlphaMode.OPAQUE, 0.5F, false, false);
        float[] positions = {-0.3F, -0.3F, 0, 0.3F, -0.3F, 0, 0, 0.3F, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1};
        GltfPrimitive primitive = new GltfPrimitive(positions, normals, new float[6], new float[6],
                new float[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, null, null,
                new float[0][], new float[0][], new int[]{2, 0, 1}, material,
                new Matrix4f(), 0, -1, List.of(new Matrix4f()));
        GltfDeformedGeometry geometry = new GltfDeformedGeometry(positions, normals);
        try (ShaderInstance shader = new ShaderInstance(resources,
                ResourceLocation.fromNamespaceAndPath("gltf_renderer_api", "gltf_icon"), DefaultVertexFormat.NEW_ENTITY);
             MemoryStack stack = MemoryStack.stackPush()) {
            canvas.clear(false);
            canvas.bindWrite(true);
            RenderSystem.setShaderTexture(0, texture.getColorTextureId());
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            var before = GltfRenderMetrics.snapshot();
            GltfShaderGeometryCache.draw(primitive, geometry, new Matrix4f().translate(-0.5F, 0, 0), 240, 0, shader);
            var first = GltfRenderMetrics.snapshot().minus(before);
            before = GltfRenderMetrics.snapshot();
            GltfShaderGeometryCache.draw(primitive, geometry, new Matrix4f().translate(0.5F, 0, 0), 240, 0, shader);
            var second = GltfRenderMetrics.snapshot().minus(before);
            if (first.uploadedBytes() <= 0 || second.uploadedBytes() != 0 || second.transformedVertices() != 0
                    || second.bufferReuses() != 1 || second.draws() != 1)
                throw new IllegalStateException("Cached geometry repeated CPU transformation or upload");
            var pixel = stack.mallocFloat(4);
            for (int x : new int[]{8, 24}) {
                GL11.glReadPixels(x, 7, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                if (pixel.get(1) < 0.99F) throw new IllegalStateException("Cached geometry ignored its per-object GPU transform");
            }
            before = GltfRenderMetrics.snapshot();
            GltfShaderGeometryCache.draw(primitive, new GltfDeformedGeometry(positions.clone(), normals.clone()),
                    new Matrix4f(), 240, 0, shader);
            if (GltfRenderMetrics.snapshot().minus(before).uploadedBytes() <= 0)
                throw new IllegalStateException("Cached geometry did not invalidate a changed animation pose");
            before = GltfRenderMetrics.snapshot();
            GltfShaderGeometryCache.draw(primitive, geometry, new Matrix4f(), 120, 0, shader);
            if (GltfRenderMetrics.snapshot().minus(before).uploadedBytes() <= 0 || GL11.glGetError() != GL11.GL_NO_ERROR)
                throw new IllegalStateException("Cached geometry did not retain independent lighting");
            System.out.println("Shader geometry cache GPU regression passed: no repeat upload/CPU transform, distinct GPU poses, animation and lighting invalidation");
        } finally {
            GltfShaderGeometryCache.clear();
            primitive.close();
        }
    }
}
