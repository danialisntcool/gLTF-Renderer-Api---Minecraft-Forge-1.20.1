package me.danialisntcool.gltfapi.client.gltf;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class GltfShaderStreamWriterTest {
    private static final VertexFormat FORMAT = format();

    static VertexFormat format() {
        var mapping = ImmutableMap.<String, VertexFormatElement>builder().putAll(DefaultVertexFormat.NEW_ENTITY.getElementMapping());
        mapping.put("iris_Entity", new VertexFormatElement(11, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.UV, 3));
        mapping.put("mc_midTexCoord", new StandaloneElement(12, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 2));
        mapping.put("at_tangent", new StandaloneElement(13, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.GENERIC, 4));
        mapping.put("Padding2", new VertexFormatElement(0, VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.PADDING, 1));
        return new VertexFormat(mapping.build());
    }

    private static GltfPrimitive primitive(int triangles, boolean blend) {
        GltfMaterial material = new GltfMaterial(null, null, null, null, null,
                0.5F, 0.75F, 1, 1, 1, 0.4F, 1, 1, 0, 0, 0,
                blend ? GltfMaterial.AlphaMode.BLEND : GltfMaterial.AlphaMode.OPAQUE, 0.5F, false, false);
        int[] indices = new int[triangles * 3];
        for (int index = 0; index < indices.length; index++) indices[index] = index % 3;
        return new GltfPrimitive(new float[]{1, 2, 3, 3, 1, 2, 0, 4, 1},
                new float[]{0.5F, 0.2F, 1, -0.3F, 0.6F, 1, 0, 0, 1},
                new float[]{0.1F, 0.2F, 0.7F, 0.3F, 0.25F, 0.9F}, new float[6],
                new float[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, null, null,
                new float[0][], new float[0][], indices, material, new Matrix4f(), 0, -1, List.of(new Matrix4f()));
    }

    private static GltfDeformedGeometry geometry(GltfPrimitive primitive) {
        return new GltfDeformedGeometry(primitive.positions(), primitive.normals());
    }

    private static GltfShaderPackCompat.EntityState ids(int base) {
        return new GltfShaderPackCompat.EntityState(base, base + 1, base + 2);
    }

    @AfterEach
    void release() {
        GltfShaderStreamWriter.clear();
        GltfStreamWriter.clear();
    }

    @Test
    void wrappedCallerReceivesBulkTrianglesWithoutFlushOrPerVertexCallbacks() {
        ShaderBuilder builder = new ShaderBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, FORMAT);
        int[] requests = {0};
        MultiBufferSource wrapper = type -> {
            requests[0]++;
            return builder;
        };
        assertFalse(wrapper instanceof MultiBufferSource.BufferSource);
        GltfPrimitive primitive = primitive(2, false);
        var before = GltfRenderMetrics.bufferedSnapshot();
        assertTrue(GltfShaderStreamWriter.append((BufferBuilder) wrapper.getBuffer(null), primitive, geometry(primitive),
                new Matrix4f(), new Matrix3f(), 0x00f00090, 0x000a0004, ids(5)));
        assertTrue(GltfShaderStreamWriter.append(builder, primitive, geometry(primitive),
                new Matrix4f().translate(10, 20, 30), new Matrix3f(), 120, 0, ids(15)));
        assertEquals(1, requests[0]);
        assertEquals(0, builder.callbacks);
        assertTrue(builder.building());
        var counts = GltfRenderMetrics.bufferedSnapshot().minus(before);
        assertEquals(2, counts.bulk());
        assertEquals(0, counts.slow());
        assertEquals(12 * 56, counts.bytes());
        var rendered = builder.end();
        try {
            assertEquals(12, rendered.drawState().vertexCount());
            assertEquals(56, rendered.drawState().format().getVertexSize());
            ByteBuffer data = rendered.vertexBuffer().order(ByteOrder.nativeOrder());
            assertEquals(5, data.getShort(36));
            assertEquals(15, data.getShort(6 * 56 + 36));
            assertEquals(11, data.getFloat(6 * 56));
            assertEquals(22, data.getFloat(6 * 56 + 4));
            assertEquals(33, data.getFloat(6 * 56 + 8));
        } finally {
            rendered.release();
        }
    }

    @Test
    void translationLightOverlayAndEntityIdsRemainIndependentAcrossCacheHits() {
        GltfPrimitive primitive = primitive(1, false);
        Matrix4f pose = new Matrix4f().rotateY(0.3F).scale(-2, 3, 4);
        Matrix3f normal = new Matrix3f(pose).invert().transpose();
        byte[] first = copy(GltfShaderStreamWriter.vertices(primitive, geometry(primitive), pose, normal, 240, 4, ids(3)));
        var before = GltfRenderMetrics.snapshot();
        pose.m30(-123).m31(456).m32(78);
        ByteBuffer second = GltfShaderStreamWriter.vertices(primitive, geometry(primitive), pose, normal, 160, 9, ids(-1));
        assertEquals(1, GltfRenderMetrics.snapshot().minus(before).bufferReuses());
        for (int vertex = 0; vertex < 3; vertex++) {
            int offset = vertex * 56;
            ByteBuffer old = ByteBuffer.wrap(first).order(ByteOrder.nativeOrder());
            assertEquals(old.getFloat(offset) - 123, second.getFloat(offset), 0.00001F);
            assertEquals(old.getFloat(offset + 4) + 456, second.getFloat(offset + 4), 0.00001F);
            assertEquals(9, second.getInt(offset + 24));
            assertEquals(160, second.getInt(offset + 28));
            assertEquals(-1, second.getShort(offset + 36));
            assertEquals(0, second.getShort(offset + 38));
            assertEquals(1, second.getShort(offset + 40));
            assertEquals(old.getInt(offset + 50), second.getInt(offset + 50));
        }
    }

    @Test
    void rotationAndDeformationInvalidateTemplate() {
        GltfPrimitive primitive = primitive(1, false);
        GltfShaderStreamWriter.vertices(primitive, geometry(primitive), new Matrix4f(), new Matrix3f(), 240, 0, ids(0));
        var before = GltfRenderMetrics.snapshot();
        Matrix4f rotated = new Matrix4f().rotateX(0.4F);
        GltfShaderStreamWriter.vertices(primitive, geometry(primitive), rotated, new Matrix3f(rotated), 240, 0, ids(0));
        assertEquals(0, GltfRenderMetrics.snapshot().minus(before).bufferReuses());
        float[] positions = primitive.positions().clone();
        positions[0] += 2;
        ByteBuffer changed = GltfShaderStreamWriter.vertices(primitive,
                new GltfDeformedGeometry(positions, primitive.normals()), new Matrix4f(), new Matrix3f(), 240, 0, ids(0));
        assertEquals(3, changed.getFloat(0));
    }

    @Test
    void consumedStagingBufferCanGrowAndBeReusedFromPositionZero() {
        for (int triangles : new int[]{30, 150, 10, 300, 30}) {
            GltfPrimitive primitive = primitive(triangles, false);
            ByteBuffer data = GltfShaderStreamWriter.vertices(primitive, geometry(primitive),
                    new Matrix4f().translate(7, 8, 9), new Matrix3f(), 240, 0, ids(12));
            assertEquals(0, data.position());
            assertEquals(triangles * 3 * 56, data.remaining());
            assertEquals(8, data.getFloat(0));
            assertEquals(12, data.getShort(data.limit() - 56 + 36));
            data.position(data.limit());
        }
    }

    @Test
    void movingCameraRecyclesMeshTemplatesAndKeepsCacheBounded() throws Exception {
        GltfPrimitive primitive = primitive(1, false);
        var field = GltfShaderStreamWriter.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        java.util.Set<byte[]> allocations = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int frame = 0; frame < 80; frame++) {
            Matrix4f pose = new Matrix4f().rotateY(frame * 0.01F);
            GltfShaderStreamWriter.vertices(primitive, geometry(primitive), pose,
                    new Matrix3f(pose).invert().transpose(), 240, 0, ids(frame));
            @SuppressWarnings("unchecked")
            var cache = (java.util.Map<Object, byte[]>) field.get(null);
            assertTrue(cache.size() <= 2);
            allocations.addAll(cache.values());
        }
        assertEquals(2, allocations.size());
    }

    @Test
    void degenerateUvsProduceFiniteVerticesAndDefinedTangent() {
        GltfPrimitive primitive = primitive(1, false);
        java.util.Arrays.fill(primitive.compatibleUvs(), 0);
        ByteBuffer data = GltfShaderStreamWriter.vertices(primitive, geometry(primitive),
                new Matrix4f(), new Matrix3f(), 240, 0, ids(0));
        for (int vertex = 0; vertex < 3; vertex++) {
            int offset = vertex * 56;
            assertTrue(Float.isFinite(data.getFloat(offset)));
            assertEquals(0, data.getInt(offset + 50) & 0x00ffffff);
            assertEquals(127, data.get(offset + 53));
        }
    }

    @Test
    void oversizedCacheEntriesUseBoundedStagingWithoutRetainingHeapTemplates() throws Exception {
        int triangles = (int) (me.danialisntcool.gltfapi.generated.ModMetadata.STATIC_ATTRIBUTE_CACHE_BYTES / (2 * 3 * 56)) + 1;
        GltfPrimitive primitive = primitive(triangles, false);
        for (int call = 0; call < 2; call++) {
            ByteBuffer data = GltfShaderStreamWriter.vertices(primitive, geometry(primitive),
                    new Matrix4f().translate(call, 0, 0), new Matrix3f(), 240, 0, ids(call));
            assertEquals(0, data.position());
            assertEquals(triangles * 3 * 56, data.limit());
            assertEquals(call + 1, data.getFloat(0));
            assertEquals(call, data.getShort(data.limit() - 56 + 36));
            assertEquals(0, data.getShort(data.limit() - 2));
            data.position(data.limit());
        }
        var field = GltfShaderStreamWriter.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) field.get(null)).isEmpty());
    }

    @Test
    void unsupportedOrIncompleteConsumersAndTransparencyFallBackWithoutWriting() {
        GltfPrimitive primitive = primitive(1, false);
        ShaderBuilder builder = new ShaderBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, FORMAT);
        builder.partial = 1;
        assertFalse(GltfShaderStreamWriter.append(builder, primitive, geometry(primitive), new Matrix4f(), new Matrix3f(), 0, 0, ids(0)));
        builder.partial = 0;
        builder.extending = false;
        assertFalse(GltfShaderStreamWriter.append(builder, primitive, geometry(primitive), new Matrix4f(), new Matrix3f(), 0, 0, ids(0)));
        builder.extending = true;
        GltfPrimitive transparent = primitive(1, true);
        assertFalse(GltfShaderStreamWriter.append(builder, transparent, geometry(transparent), new Matrix4f(), new Matrix3f(), 0, 0, ids(0)));
        assertFalse(GltfShaderStreamWriter.append(builder, primitive, geometry(primitive), new Matrix4f(), new Matrix3f(), 0, 0, null));
        assertTrue(builder.isCurrentBatchEmpty());
        builder.endOrDiscardIfEmpty();
        builder.begin(VertexFormat.Mode.QUADS, FORMAT);
        assertFalse(GltfShaderStreamWriter.append(builder, primitive, geometry(primitive), new Matrix4f(), new Matrix3f(), 0, 0, ids(0)));
        builder.endOrDiscardIfEmpty();
        assertFalse(GltfShaderStreamWriter.compatibleFormat(DefaultVertexFormat.NEW_ENTITY));
    }

    @Test
    void shadersWithoutExtendedAttributesUseStandardBulkLayout() {
        ShaderBuilder builder = new ShaderBuilder();
        builder.extending = false;
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        GltfPrimitive primitive = primitive(1, false);
        assertTrue(GltfShaderStreamWriter.append(builder, primitive, geometry(primitive),
                new Matrix4f().translate(7, 8, 9), new Matrix3f(), 160, 4, null));
        assertEquals(0, builder.callbacks);
        var rendered = builder.end();
        try {
            assertEquals(3 * 36, rendered.vertexBuffer().remaining());
            assertEquals(8, rendered.vertexBuffer().order(ByteOrder.nativeOrder()).getFloat(0));
        } finally {
            rendered.release();
        }
    }

    @Test
    void actualOculusEntityLayoutAndSmoothTangentsMatch() throws Exception {
        String path = System.getenv("GLTF_OCULUS_MAPPED_TEST_JAR");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank());
        try (var loader = new OculusLoader(path)) {
            Class<?> formats = loader.loadClass("net.irisshaders.iris.vertices.IrisVertexFormats");
            assertTrue(GltfShaderStreamWriter.compatibleFormat((VertexFormat) formats.getField("ENTITY").get(null)));
            Class<?> viewType = loader.loadClass("net.irisshaders.iris.vertices.views.TriView");
            var tangent = loader.loadClass("net.irisshaders.iris.vertices.NormalHelper")
                    .getMethod("computeTangentSmooth", float.class, float.class, float.class, viewType);
            GltfPrimitive primitive = primitive(1, false);
            for (Matrix4f pose : List.of(new Matrix4f(), new Matrix4f().rotateXYZ(0.4F, -0.7F, 0.2F),
                    new Matrix4f().rotateY(0.8F).scale(-2, 3, 0.4F))) {
                ByteBuffer data = GltfShaderStreamWriter.vertices(primitive, geometry(primitive), pose,
                        new Matrix3f(pose).invert().transpose(), 240, 0, ids(3));
                Object view = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{viewType}, (proxy, method, args) -> {
                    int index = (int) args[0] * 56;
                    return data.getFloat(index + switch (method.getName()) {
                        case "x" -> 0;
                        case "y" -> 4;
                        case "z" -> 8;
                        case "u" -> 16;
                        case "v" -> 20;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
                });
                for (int vertex = 0; vertex < 3; vertex++) {
                    int offset = vertex * 56;
                    int expected = (int) tangent.invoke(null, data.get(offset + 32) * (1.0F / 127),
                            data.get(offset + 33) * (1.0F / 127), data.get(offset + 34) * (1.0F / 127), view);
                    int actual = data.getInt(offset + 50);
                    for (int channel = 0; channel < 3; channel++)
                        assertEquals((byte) (expected >>> (channel * 8)), (byte) (actual >>> (channel * 8)), 1);
                    assertEquals(expected >>> 24, actual >>> 24);
                    Vector3f transformed = pose.transformPosition(new Vector3f(primitive.positions()[vertex * 3],
                            primitive.positions()[vertex * 3 + 1], primitive.positions()[vertex * 3 + 2]));
                    assertEquals(transformed.x, data.getFloat(offset), 0.00001F);
                }
            }
        }
    }

    private static byte[] copy(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    public static final class StandaloneElement extends VertexFormatElement {
        public StandaloneElement(int index, Type type, Usage usage, int count) {
            super(usage == Usage.UV ? index : 0, type, usage, count);
            try {
                var field = VertexFormatElement.class.getDeclaredField("index");
                field.setAccessible(true);
                field.setInt(this, index);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class OculusLoader extends URLClassLoader {
        private OculusLoader(String path) throws Exception {
            super(new java.net.URL[]{Path.of(path).toUri().toURL()}, GltfShaderStreamWriterTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals("net.irisshaders.iris.vertices.IrisVertexFormats")) return super.findClass(name);
            try (var input = findResource(name.replace('.', '/') + ".class").openStream()) {
                var reader = new org.objectweb.asm.ClassReader(input);
                var writer = new org.objectweb.asm.ClassWriter(0);
                String element = "com/mojang/blaze3d/vertex/VertexFormatElement";
                String replacement = StandaloneElement.class.getName().replace('.', '/');
                reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int access, String method, String descriptor,
                                                                       String signature, String[] exceptions) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9,
                                super.visitMethod(access, method, descriptor, signature, exceptions)) {
                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                super.visitTypeInsn(opcode, opcode == org.objectweb.asm.Opcodes.NEW && type.equals(element) ? replacement : type);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                                super.visitMethodInsn(opcode, owner.equals(element) && method.equals("<init>") ? replacement : owner,
                                        method, descriptor, isInterface);
                            }
                        };
                    }
                }, 0);
                byte[] definition = writer.toByteArray();
                return defineClass(name, definition, 0, definition.length);
            } catch (java.io.IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }
    }

    public static final class ShaderBuilder extends BufferBuilder {
        private int callbacks;
        private int partial;
        private boolean extending = true;
        private VertexFormat.Mode currentMode;
        private VertexFormat currentFormat;

        public ShaderBuilder() { super(1024); }
        public VertexFormat iris$format() { return currentFormat; }
        public VertexFormat.Mode iris$mode() { return currentMode; }
        public boolean iris$extending() { return extending; }
        public int iris$vertexCount() { return partial; }

        @Override
        public void begin(VertexFormat.Mode mode, VertexFormat format) {
            super.begin(mode, format);
            currentMode = mode;
            currentFormat = format;
        }

        @Override
        public void endVertex() {
            callbacks++;
            super.endVertex();
        }
    }
}
