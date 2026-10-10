package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import java.util.LinkedHashMap;
import java.util.Map;

final class GltfShaderGeometryCache {
    private static final Map<Key, GltfGpuBuffer> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final BufferBuilder STAGING = new BufferBuilder(4096);
    private static long bytes;

    private GltfShaderGeometryCache() {
    }

    static boolean canCache(GltfPrimitive primitive) {
        return (long) primitive.indices().length * (DefaultVertexFormat.NEW_ENTITY.getVertexSize() + 36)
                <= Math.min(ModMetadata.STAGING_BYTES, ModMetadata.GPU_STREAM_CACHE_BYTES / 2);
    }

    static void draw(GltfPrimitive primitive, GltfDeformedGeometry geometry, Matrix4f pose,
                     int light, int overlay, ShaderInstance shader) {
        Key key = new Key(primitive, geometry.positions(), geometry.normals(), light, overlay, shader,
                GltfShaderPackCompat.captureEntityState());
        GltfGpuBuffer buffer = CACHE.get(key);
        boolean retained = true;
        if (buffer == null) {
            buffer = build(primitive, geometry, light, overlay);
            retained = buffer.storageBytes() <= ModMetadata.GPU_STREAM_CACHE_BYTES / 2;
            if (retained) {
                CACHE.put(key, buffer);
                bytes += buffer.storageBytes();
                trim();
            }
        } else GltfRenderMetrics.reuse();
        try {
            buffer.drawWithShader(new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose),
                    RenderSystem.getProjectionMatrix(), shader);
        } finally {
            if (!retained) buffer.close();
        }
    }

    private static GltfGpuBuffer build(GltfPrimitive primitive, GltfDeformedGeometry geometry,
                                      int light, int overlay) {
        BufferBuilder builder = STAGING;
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        GltfMaterial material = primitive.material();
        int[] indices = primitive.indices();
        int[] sequential = new int[indices.length];
        float[] uv = primitive.compatibleUvs();
        float[] colors = primitive.colors();
        for (int vertex = 0; vertex < indices.length; vertex++) {
            int index = indices[vertex];
            int vector = index * 3;
            int texture = index * 2;
            int color = index * 4;
            builder.vertex(geometry.positions()[vector], geometry.positions()[vector + 1], geometry.positions()[vector + 2])
                    .color(colors[color] * material.red(), colors[color + 1] * material.green(),
                            colors[color + 2] * material.blue(), colors[color + 3] * material.alpha())
                    .uv(uv[texture], uv[texture + 1]).overlayCoords(overlay)
                    .uv2(material.unlit() ? 15728880 : light)
                    .normal(geometry.normals()[vector], geometry.normals()[vector + 1], geometry.normals()[vector + 2])
                    .endVertex();
            sequential[vertex] = vertex;
        }
        var rendered = builder.end();
        try {
            return new GltfGpuBuffer(rendered.drawState().format(), rendered, sequential);
        } finally {
            rendered.release();
        }
    }

    private static void trim() {
        while (CACHE.size() > 256 || bytes > ModMetadata.GPU_STREAM_CACHE_BYTES / 2) {
            var iterator = CACHE.entrySet().iterator();
            GltfGpuBuffer buffer = iterator.next().getValue();
            iterator.remove();
            bytes -= buffer.storageBytes();
            buffer.close();
        }
    }

    static void clear() {
        for (GltfGpuBuffer buffer : CACHE.values()) buffer.close();
        CACHE.clear();
        bytes = 0;
    }

    private record Key(GltfPrimitive primitive, float[] positions, float[] normals, int light, int overlay,
                       ShaderInstance shader, GltfShaderPackCompat.EntityState entityState) {
    }
}
