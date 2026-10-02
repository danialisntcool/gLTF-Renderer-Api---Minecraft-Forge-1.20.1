package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.vertex.BufferBuilder;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class GltfStreamWriter {
    private static ByteBuffer unique;
    private static ByteBuffer expanded;
    private static final java.util.Map<TemplateKey, byte[]> TEMPLATES = new java.util.LinkedHashMap<>(16, 0.75F, true);
    private static long templateBytes;

    private GltfStreamWriter() {
    }

    static boolean append(BufferBuilder builder, GltfPrimitive primitive, GltfDeformedGeometry geometry,
                          Matrix4f pose, Matrix3f normal, int[] indices, int light, int overlay, boolean pbr) {
        int stride = pbr ? 44 : 36;
        long sourceBytes = (long) geometry.positions().length / 3 * stride;
        long outputBytes = (long) indices.length * stride;
        if (sourceBytes + outputBytes > ModMetadata.STAGING_BYTES) return false;
        ByteBuffer vertices = indexed(primitive, geometry, pose, normal, light, overlay, pbr);
        if (vertices == null) return false;
        expanded = ensure(expanded, (int) outputBytes);
        long source = MemoryUtil.memAddress(vertices);
        long destination = MemoryUtil.memAddress(expanded);
        for (int index : indices) {
            MemoryUtil.memCopy(source + (long) index * stride, destination, stride);
            destination += stride;
        }
        expanded.position(0).limit((int) outputBytes);
        builder.putBulkData(expanded);
        GltfRenderMetrics.stream(geometry.positions().length / 3, indices.length);
        return true;
    }

    static ByteBuffer indexed(GltfPrimitive primitive, GltfDeformedGeometry geometry,
                              Matrix4f pose, Matrix3f normal, int light, int overlay, boolean pbr) {
        int stride = pbr ? 44 : 36;
        int vertexCount = geometry.positions().length / 3;
        long bytes = (long) vertexCount * stride;
        if (bytes > ModMetadata.STAGING_BYTES) return null;
        unique = ensure(unique, (int) bytes);
        byte[] template = template(primitive, pbr, vertexCount, stride);
        if (template != null) unique.put(template).position(0);
        long address = MemoryUtil.memAddress(unique);
        float[] positions = geometry.positions();
        float[] normals = geometry.normals();
        float[] uv = pbr ? primitive.textureCoordinates() : primitive.compatibleUvs();
        float[] uv1 = primitive.secondaryTextureCoordinates();
        float[] colors = primitive.colors();
        GltfMaterial material = primitive.material();
        float red = pbr ? 1.0F : material.red();
        float green = pbr ? 1.0F : material.green();
        float blue = pbr ? 1.0F : material.blue();
        float alpha = pbr ? 1.0F : material.alpha();
        int packedLight = material.unlit() && !pbr ? 15728880 : light;
        for (int index = 0; index < vertexCount; index++) {
            int vector = index * 3;
            int texture = index * 2;
            int color = index * 4;
            float x = positions[vector];
            float y = positions[vector + 1];
            float z = positions[vector + 2];
            MemoryUtil.memPutFloat(address, pose.m00() * x + pose.m10() * y + pose.m20() * z + pose.m30());
            MemoryUtil.memPutFloat(address + 4, pose.m01() * x + pose.m11() * y + pose.m21() * z + pose.m31());
            MemoryUtil.memPutFloat(address + 8, pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32());
            if (template == null) {
            MemoryUtil.memPutByte(address + 12, (byte) (colors[color] * red * 255.0F));
            MemoryUtil.memPutByte(address + 13, (byte) (colors[color + 1] * green * 255.0F));
            MemoryUtil.memPutByte(address + 14, (byte) (colors[color + 2] * blue * 255.0F));
            MemoryUtil.memPutByte(address + 15, (byte) (colors[color + 3] * alpha * 255.0F));
            MemoryUtil.memPutFloat(address + 16, uv[texture]);
            MemoryUtil.memPutFloat(address + 20, uv[texture + 1]);
            }
            MemoryUtil.memPutInt(address + 24, overlay);
            MemoryUtil.memPutInt(address + 28, packedLight);
            x = normals[vector];
            y = normals[vector + 1];
            z = normals[vector + 2];
            float nx = normal.m00() * x + normal.m10() * y + normal.m20() * z;
            float ny = normal.m01() * x + normal.m11() * y + normal.m21() * z;
            float nz = normal.m02() * x + normal.m12() * y + normal.m22() * z;
            float length = nx * nx + ny * ny + nz * nz;
            float inverse = length > 0.0F ? (float) (1.0 / Math.sqrt(length)) : 0.0F;
            MemoryUtil.memPutByte(address + 32, normalByte(nx * inverse));
            MemoryUtil.memPutByte(address + 33, normalByte(ny * inverse));
            MemoryUtil.memPutByte(address + 34, normalByte(nz * inverse));
            MemoryUtil.memPutByte(address + 35, (byte) 0);
            if (pbr && template == null) {
                MemoryUtil.memPutFloat(address + 36, uv1[texture]);
                MemoryUtil.memPutFloat(address + 40, uv1[texture + 1]);
            }
            address += stride;
        }
        unique.position(0).limit((int) bytes);
        return unique;
    }

    private static byte[] template(GltfPrimitive primitive, boolean pbr, int vertexCount, int stride) {
        long size = (long) vertexCount * stride;
        if (size > ModMetadata.STATIC_ATTRIBUTE_CACHE_BYTES || size == 0) return null;
        TemplateKey key = new TemplateKey(primitive, pbr);
        byte[] cached = TEMPLATES.get(key);
        if (cached != null) return cached;
        while (templateBytes + size > ModMetadata.STATIC_ATTRIBUTE_CACHE_BYTES && !TEMPLATES.isEmpty()) {
            var iterator = TEMPLATES.entrySet().iterator();
            templateBytes -= iterator.next().getValue().length;
            iterator.remove();
        }
        ByteBuffer data = ByteBuffer.allocate((int) size).order(ByteOrder.nativeOrder());
        float[] uv = pbr ? primitive.textureCoordinates() : primitive.compatibleUvs();
        float[] uv1 = primitive.secondaryTextureCoordinates();
        float[] colors = primitive.colors();
        GltfMaterial material = primitive.material();
        for (int index = 0; index < vertexCount; index++) {
            int offset = index * stride;
            int color = index * 4;
            data.put(offset + 12, (byte) (colors[color] * (pbr ? 1 : material.red()) * 255));
            data.put(offset + 13, (byte) (colors[color + 1] * (pbr ? 1 : material.green()) * 255));
            data.put(offset + 14, (byte) (colors[color + 2] * (pbr ? 1 : material.blue()) * 255));
            data.put(offset + 15, (byte) (colors[color + 3] * (pbr ? 1 : material.alpha()) * 255));
            data.putFloat(offset + 16, uv[index * 2]);
            data.putFloat(offset + 20, uv[index * 2 + 1]);
            if (pbr) {
                data.putFloat(offset + 36, uv1[index * 2]);
                data.putFloat(offset + 40, uv1[index * 2 + 1]);
            }
        }
        cached = data.array();
        TEMPLATES.put(key, cached);
        templateBytes += size;
        return cached;
    }

    private static byte normalByte(float value) {
        return (byte) (Math.max(-1.0F, Math.min(1.0F, value)) * 127.0F);
    }

    private static ByteBuffer ensure(ByteBuffer buffer, int bytes) {
        if (buffer != null && buffer.capacity() >= bytes) return buffer.clear();
        int capacity = Math.min(ModMetadata.STAGING_BYTES, Math.max(bytes, Math.max(4096,
                buffer == null ? 0 : buffer.capacity() * 2)));
        return (buffer == null ? MemoryUtil.memAlloc(capacity) : MemoryUtil.memRealloc(buffer, capacity))
                .order(ByteOrder.nativeOrder());
    }

    static void clear() {
        if (unique != null) MemoryUtil.memFree(unique);
        if (expanded != null) MemoryUtil.memFree(expanded);
        unique = null;
        expanded = null;
        TEMPLATES.clear();
        templateBytes = 0;
    }

    private record TemplateKey(GltfPrimitive primitive, boolean pbr) {
    }
}
