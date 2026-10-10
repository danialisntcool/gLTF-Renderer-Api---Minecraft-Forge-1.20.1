package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.util.List;

public final class GltfShaderStreamSmoke {
    public static void verify(ResourceProvider resources, TextureTarget texture, TextureTarget canvas) throws Exception {
        GltfMaterial material = new GltfMaterial(null, null, null, null, null,
                1, 1, 1, 1, 1, 0.3F, 1, 1, 0, 0, 0, GltfMaterial.AlphaMode.OPAQUE, 0.5F, false, false);
        GltfPrimitive primitive = new GltfPrimitive(new float[]{-0.3F, -0.3F, 0, 0.3F, -0.3F, 0, 0, 0.3F, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1}, new float[6], new float[6],
                new float[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, null, null,
                new float[0][], new float[0][], new int[]{0, 1, 2}, material,
                new Matrix4f(), 0, -1, List.of(new Matrix4f()));
        try (GltfNativeState ignored = new GltfNativeState();
             ShaderInstance shader = new ShaderInstance(resources,
                     ResourceLocation.fromNamespaceAndPath("gltf_renderer_api", "gltf_icon"), DefaultVertexFormat.NEW_ENTITY);
             MemoryStack stack = MemoryStack.stackPush()) {
            canvas.clear(false);
            canvas.bindWrite(true);
            RenderSystem.setShaderTexture(0, texture.getColorTextureId());
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            var builder = new GltfShaderStreamWriterTest.ShaderBuilder();
            builder.begin(VertexFormat.Mode.TRIANGLES, GltfShaderStreamWriterTest.format());
            MultiBufferSource wrapper = type -> builder;
            var geometry = new GltfDeformedGeometry(primitive.positions(), primitive.normals());
            for (float x : new float[]{-0.5F, 0.5F}) {
                if (!GltfShaderStreamWriter.append((com.mojang.blaze3d.vertex.BufferBuilder) wrapper.getBuffer(null),
                        primitive, geometry, new Matrix4f().translate(x, 0, 0), new Matrix3f(), 240, 0,
                        new GltfShaderPackCompat.EntityState(0, (int) (x * 10), 0)))
                    throw new IllegalStateException("Wrapped Oculus submission skipped the bulk path");
            }
            var pixel = stack.mallocFloat(4);
            GL11.glReadPixels(8, 7, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            if (pixel.get(1) != 0) throw new IllegalStateException("Wrapped submission bypassed caller ordering");
            var rendered = builder.end();
            try (var gpu = new GltfGpuBuffer(rendered.drawState().format(), rendered, new int[]{0, 1, 2, 3, 4, 5})) {
                gpu.drawWithShader(new Matrix4f(), new Matrix4f(), shader);
            } finally {
                rendered.release();
            }
            for (int x : new int[]{8, 24}) {
                GL11.glReadPixels(x, 7, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                if (pixel.get(1) < 0.99F) throw new IllegalStateException("Bulk shader stream lost its geometry or per-object transform");
            }
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("Bulk shader stream produced a GL error");
            System.out.println("Wrapped shader stream GPU regression passed: deferred batching, cached triangles and separate object transforms");
        } finally {
            GltfShaderStreamWriter.clear();
            GltfStreamWriter.clear();
            primitive.close();
        }
    }
}
