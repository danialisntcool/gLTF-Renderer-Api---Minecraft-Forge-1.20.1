package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

final class GltfShaderStreamWriter {
    private static final int STRIDE = 56;
    private static final Map<Key, byte[]> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> type) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new Access(method(lookup, type, "iris$format", VertexFormat.class),
                        method(lookup, type, "iris$mode", VertexFormat.Mode.class),
                        method(lookup, type, "iris$extending", boolean.class),
                        method(lookup, type, "iris$vertexCount", int.class));
            } catch (ReflectiveOperationException exception) {
                return new Access(null, null, null, null);
            }
        }
    };
    private static ByteBuffer staging;
    private static long bytes;

    private GltfShaderStreamWriter() {
    }

    private static MethodHandle method(MethodHandles.Lookup lookup, Class<?> type, String name,
                                       Class<?> result) throws ReflectiveOperationException {
        return lookup.unreflect(type.getMethod(name)).asType(MethodType.methodType(result, Object.class));
    }

    static boolean append(BufferBuilder builder, GltfPrimitive primitive, GltfDeformedGeometry geometry,
                          Matrix4f pose, Matrix3f normal, int light, int overlay,
                          GltfShaderPackCompat.EntityState state) {
        int layout = ACCESS.get(builder.getClass()).layout(builder);
        if (layout == 0 || primitive.material().alphaMode() == GltfMaterial.AlphaMode.BLEND) return false;
        if (layout == 1) {
            if (!GltfStreamWriter.append(builder, primitive, geometry, pose, normal,
                    primitive.indices(), light, overlay, false)) return false;
            GltfRenderMetrics.buffered(true, (long) primitive.indices().length * 36);
            return true;
        }
        if (state == null) return false;
        ByteBuffer vertices = vertices(primitive, geometry, pose, normal, light, overlay, state);
        if (vertices == null) return false;
        builder.putBulkData(vertices);
        GltfRenderMetrics.buffered(true, vertices.limit());
        return true;
    }

    static boolean compatibleFormat(VertexFormat format) {
        if (format.getVertexSize() != STRIDE) return false;
        var elements = format.getElements();
        var standard = DefaultVertexFormat.NEW_ENTITY.getElements();
        if (elements.size() != standard.size() + 4 || !elements.subList(0, standard.size()).equals(standard)) return false;
        return attribute(format, "iris_Entity", 36, VertexFormatElement.Type.USHORT, 3)
                && attribute(format, "mc_midTexCoord", 42, VertexFormatElement.Type.FLOAT, 2)
                && attribute(format, "at_tangent", 50, VertexFormatElement.Type.BYTE, 4)
                && attribute(format, "Padding2", 54, VertexFormatElement.Type.SHORT, 1);
    }

    private static boolean attribute(VertexFormat format, String name, int offset,
                                     VertexFormatElement.Type type, int count) {
        int index = format.getElementAttributeNames().indexOf(name);
        if (index < 0 || format.getOffset(index) != offset) return false;
        VertexFormatElement element = format.getElements().get(index);
        return element.getType() == type && element.getCount() == count;
    }

    static ByteBuffer vertices(GltfPrimitive primitive, GltfDeformedGeometry geometry,
                               Matrix4f pose, Matrix3f normal, int light, int overlay,
                               GltfShaderPackCompat.EntityState state) {
        long size = (long) primitive.indices().length * STRIDE;
        long uniqueSize = (long) geometry.positions().length / 3 * 36;
        if ((pose.properties() & Matrix4f.PROPERTY_AFFINE) == 0 || size == 0
                || size + uniqueSize > ModMetadata.STAGING_BYTES || primitive.indices().length % 3 != 0) return null;
        Matrix4f linear = new Matrix4f(pose).m30(0).m31(0).m32(0);
        Key key = new Key(primitive, geometry.positions(), geometry.normals(), new Matrix3f(linear), new Matrix3f(normal));
        staging = ensure(staging, (int) size);
        byte[] template = CACHE.get(key);
        if (template == null) {
            long budget = ModMetadata.STATIC_ATTRIBUTE_CACHE_BYTES / 2;
            if (size <= budget) {
                template = reusable(primitive);
                if (template == null) template = new byte[(int) size];
                build(primitive, geometry, linear, normal, ByteBuffer.wrap(template).order(ByteOrder.nativeOrder()));
                while (!CACHE.isEmpty() && (CACHE.size() >= 256 || bytes + size > budget)) {
                    var iterator = CACHE.entrySet().iterator();
                    bytes -= iterator.next().getValue().length;
                    iterator.remove();
                }
                CACHE.put(key, template);
                bytes += size;
            } else build(primitive, geometry, linear, normal, staging);
        } else GltfRenderMetrics.reuse();
        if (template != null) staging.put(template).position(0);
        long address = MemoryUtil.memAddress(staging);
        int packedLight = primitive.material().unlit() ? 15728880 : light;
        for (int vertex = 0; vertex < primitive.indices().length; vertex++, address += STRIDE) {
            MemoryUtil.memPutFloat(address, MemoryUtil.memGetFloat(address) + pose.m30());
            MemoryUtil.memPutFloat(address + 4, MemoryUtil.memGetFloat(address + 4) + pose.m31());
            MemoryUtil.memPutFloat(address + 8, MemoryUtil.memGetFloat(address + 8) + pose.m32());
            MemoryUtil.memPutInt(address + 24, overlay);
            MemoryUtil.memPutInt(address + 28, packedLight);
            MemoryUtil.memPutShort(address + 36, (short) state.entity());
            MemoryUtil.memPutShort(address + 38, (short) state.blockEntity());
            MemoryUtil.memPutShort(address + 40, (short) state.item());
        }
        GltfRenderMetrics.stream(primitive.indices().length, primitive.indices().length);
        return staging.position(0).limit((int) size);
    }

    private static byte[] reusable(GltfPrimitive primitive) {
        Key oldest = null;
        int variants = 0;
        for (Key key : CACHE.keySet()) {
            if (key.primitive != primitive) continue;
            if (oldest == null) oldest = key;
            variants++;
        }
        if (variants < 2) return null;
        byte[] template = CACHE.remove(oldest);
        bytes -= template.length;
        return template;
    }

    private static void build(GltfPrimitive primitive, GltfDeformedGeometry geometry,
                               Matrix4f linear, Matrix3f normal, ByteBuffer result) {
        ByteBuffer unique = GltfStreamWriter.indexed(primitive, geometry, linear, normal, 0, 0, false);
        int[] indices = primitive.indices();
        for (int vertex = 0; vertex < indices.length; vertex++) {
            int source = indices[vertex] * 36;
            int offset = vertex * STRIDE;
            for (int word = 0; word < 36; word += 4) result.putInt(offset + word, unique.getInt(source + word));
        }
        for (int triangle = 0; triangle < indices.length; triangle += 3) {
            int start = triangle * STRIDE;
            float u = (result.getFloat(start + 16) + result.getFloat(start + STRIDE + 16)
                    + result.getFloat(start + 2 * STRIDE + 16)) / 3;
            float v = (result.getFloat(start + 20) + result.getFloat(start + STRIDE + 20)
                    + result.getFloat(start + 2 * STRIDE + 20)) / 3;
            for (int corner = 0; corner < 3; corner++) {
                int offset = start + corner * STRIDE;
                result.putFloat(offset + 42, u);
                result.putFloat(offset + 46, v);
                result.putInt(offset + 50, tangent(result, start, offset));
                result.putShort(offset + 54, (short) 0);
            }
        }
        GltfRenderMetrics.stream(geometry.positions().length / 3, 0);
    }

    private static int tangent(ByteBuffer data, int triangle, int vertex) {
        float nx = data.get(vertex + 32) * (1.0F / 127);
        float ny = data.get(vertex + 33) * (1.0F / 127);
        float nz = data.get(vertex + 34) * (1.0F / 127);
        float ax = data.getFloat(triangle + STRIDE) - data.getFloat(triangle);
        float ay = data.getFloat(triangle + STRIDE + 4) - data.getFloat(triangle + 4);
        float az = data.getFloat(triangle + STRIDE + 8) - data.getFloat(triangle + 8);
        float bx = data.getFloat(triangle + 2 * STRIDE) - data.getFloat(triangle);
        float by = data.getFloat(triangle + 2 * STRIDE + 4) - data.getFloat(triangle + 4);
        float bz = data.getFloat(triangle + 2 * STRIDE + 8) - data.getFloat(triangle + 8);
        float aDot = ax * nx + ay * ny + az * nz;
        float bDot = bx * nx + by * ny + bz * nz;
        ax -= aDot * nx;
        ay -= aDot * ny;
        az -= aDot * nz;
        bx -= bDot * nx;
        by -= bDot * ny;
        bz -= bDot * nz;
        float au = data.getFloat(triangle + STRIDE + 16) - data.getFloat(triangle + 16);
        float av = data.getFloat(triangle + STRIDE + 20) - data.getFloat(triangle + 20);
        float bu = data.getFloat(triangle + 2 * STRIDE + 16) - data.getFloat(triangle + 16);
        float bv = data.getFloat(triangle + 2 * STRIDE + 20) - data.getFloat(triangle + 20);
        float determinant = au * bv - bu * av;
        float inverse = determinant == 0 ? 1 : 1 / determinant;
        float tx = inverse * (bv * ax - av * bx);
        float ty = inverse * (bv * ay - av * by);
        float tz = inverse * (bv * az - av * bz);
        float length = tx * tx + ty * ty + tz * tz;
        float normalization = length == 0 ? 1 : (float) (1 / Math.sqrt(length));
        tx *= normalization;
        ty *= normalization;
        tz *= normalization;
        float sx = inverse * (-bu * ax + au * bx);
        float sy = inverse * (-bu * ay + au * by);
        float sz = inverse * (-bu * az + au * bz);
        float handedness = sx * (ty * nz - tz * ny) + sy * (tz * nx - tx * nz)
                + sz * (tx * ny - ty * nx) < 0 ? -1 : 1;
        return packed(tx) | packed(ty) << 8 | packed(tz) << 16 | packed(handedness) << 24;
    }

    private static int packed(float value) {
        return (int) (value * 127) & 255;
    }

    private static ByteBuffer ensure(ByteBuffer buffer, int size) {
        if (buffer != null && buffer.capacity() >= size) return buffer.clear();
        int capacity = (int) Math.min(ModMetadata.STAGING_BYTES, Math.max(size,
                Math.max(4096L, buffer == null ? 0 : (long) buffer.capacity() * 2)));
        return (buffer == null ? MemoryUtil.memAlloc(capacity) : MemoryUtil.memRealloc(buffer, capacity))
                .clear().order(ByteOrder.nativeOrder());
    }

    static void clear() {
        if (staging != null) MemoryUtil.memFree(staging);
        staging = null;
        CACHE.clear();
        bytes = 0;
    }

    private record Key(GltfPrimitive primitive, float[] positions, float[] normals, Matrix3f pose, Matrix3f normal) {
    }

    private static final class Access {
        private final MethodHandle format;
        private final MethodHandle mode;
        private final MethodHandle extending;
        private final MethodHandle vertexCount;
        private VertexFormat lastFormat;
        private boolean compatible;

        private Access(MethodHandle format, MethodHandle mode, MethodHandle extending, MethodHandle vertexCount) {
            this.format = format;
            this.mode = mode;
            this.extending = extending;
            this.vertexCount = vertexCount;
        }

        private int layout(Object builder) {
            if (format == null) return 0;
            try {
                if ((int) vertexCount.invokeExact(builder) != 0
                        || (VertexFormat.Mode) mode.invokeExact(builder) != VertexFormat.Mode.TRIANGLES) return 0;
                VertexFormat current = (VertexFormat) format.invokeExact(builder);
                if (!(boolean) extending.invokeExact(builder)) return current == DefaultVertexFormat.NEW_ENTITY ? 1 : 0;
                if (lastFormat != current) {
                    lastFormat = current;
                    compatible = compatibleFormat(current);
                }
                return compatible ? 2 : 0;
            } catch (Throwable exception) {
                if (exception instanceof VirtualMachineError fatal) throw fatal;
                if (exception instanceof ThreadDeath fatal) throw fatal;
                return 0;
            }
        }
    }
}
