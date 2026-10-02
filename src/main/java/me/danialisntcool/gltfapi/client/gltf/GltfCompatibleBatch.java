package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.danialisntcool.gltfapi.GltfRendererApi;
import me.danialisntcool.gltfapi.api.client.GltfNodeRotationOffsets;
import me.danialisntcool.gltfapi.api.client.GltfMaterialRenderers;
import me.danialisntcool.gltfapi.api.client.GltfRenderMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;
import me.danialisntcool.gltfapi.generated.ModMetadata;

public final class GltfCompatibleBatch implements AutoCloseable {
    private static final Map<VertexFormat, ArrayDeque<GeometryGroup>> FREE = new LinkedHashMap<>();
    private static long retainedBytes;
    private final boolean shadowPass;
    private final Map<RenderType, GeometryGroup> groups = new LinkedHashMap<>();
    private final Map<StateKey, GltfRenderState> states = new LinkedHashMap<>();
    private final List<TransparentCall> transparent = new ArrayList<>();

    public GltfCompatibleBatch(boolean shadowPass) {
        this.shadowPass = shadowPass;
    }

    public void add(GltfModel model, Matrix4f pose, Matrix3f normal, MultiBufferSource buffers,
                    int packedLight, int packedOverlay, me.danialisntcool.gltfapi.api.client.GltfRenderOptions options) {
        add(model, pose, normal, buffers, packedLight, packedOverlay, options, GltfRenderMode.AUTO);
    }

    public void add(GltfModel model, Matrix4f pose, Matrix3f normal,
                    MultiBufferSource buffers, int packedLight, int packedOverlay,
                    me.danialisntcool.gltfapi.api.client.GltfRenderOptions options, GltfRenderMode mode) {
        boolean pbr = GltfRenderer.usesBufferedPbr(mode);
        GltfNodeRotationOffsets rotationOffsets = options.nodeRotationOffsets();
        StateKey stateKey = new StateKey(model, options.animation(),
                Float.floatToIntBits(options.animationTimeSeconds()), options.scene(),
                rotationOffsets, rotationOffsets.revision());
        GltfRenderState state = states.computeIfAbsent(stateKey, ignored -> model.renderState(
                options.animation(), options.animationTimeSeconds(), options.scene(), rotationOffsets));
        for (GltfPrimitive primitive : model.primitives()) {
            if (!state.visibleNodes().get(primitive.nodeIndex())) {
                continue;
            }
            Matrix4f nodeTransform = state.transforms()[primitive.nodeIndex()];
            GltfDeformedGeometry geometry = GltfRenderer.deformedGeometry(model, state, primitive);
            for (Matrix4f instance : primitive.instances()) {
                Matrix4f localTransform = new Matrix4f(nodeTransform).mul(instance);
                if ((primitive.material().alphaMode() == GltfMaterial.AlphaMode.BLEND && !shadowPass)
                        || GltfMaterialRenderers.find(model.location()) != null) {
                    Vector3f center = GltfRenderer.primitiveCenter(primitive, localTransform, pose);
                    transparent.add(new TransparentCall(model, state, primitive, localTransform,
                            new Matrix4f(pose), new Matrix3f(normal), buffers,
                            packedLight, packedOverlay, center.lengthSquared(), pbr));
                    continue;
                }
                if (pbr) {
                    RenderType type = GltfBufferedPbrTypes.get(primitive.material());
                    groups.computeIfAbsent(type, ignored -> acquire(type.format()))
                            .add(primitive, geometry, new Matrix4f(pose).mul(localTransform),
                                    packedLight, packedOverlay, true);
                    continue;
                }
                RenderType renderType = GltfRenderer.compatibleRenderType(primitive.material(), shadowPass);
                groups.computeIfAbsent(renderType, ignored -> acquire(renderType.format()))
                        .add(primitive, geometry, new Matrix4f(pose).mul(localTransform),
                                packedLight, packedOverlay, false);
            }
        }
    }

    public void flush() {
        RenderSystem.assertOnRenderThread();
        try {
            for (Map.Entry<RenderType, GeometryGroup> entry : groups.entrySet()) {
                entry.getValue().draw(entry.getKey());
            }
            for (GeometryGroup group : groups.values()) release(group);
            groups.clear();
            transparent.sort(Comparator.comparingDouble(TransparentCall::distanceSquared).reversed());
            for (TransparentCall call : transparent) {
                drawBufferedCall(call);
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        for (GeometryGroup group : groups.values()) release(group);
        groups.clear();
        states.clear();
        transparent.clear();
    }

    private static GeometryGroup acquire(VertexFormat format) {
        ArrayDeque<GeometryGroup> pool = FREE.get(format);
        GeometryGroup group = pool == null ? null : pool.pollFirst();
        if (group == null) return new GeometryGroup(format);
        retainedBytes -= group.geometry.retainedBytes();
        group.geometry.begin();
        return group;
    }

    private static void release(GeometryGroup group) {
        group.reset();
        if (retainedBytes + group.geometry.retainedBytes() > ModMetadata.STAGING_BYTES) {
            group.geometry.close();
            return;
        }
        FREE.computeIfAbsent(group.geometry.format, ignored -> new ArrayDeque<>()).addFirst(group);
        retainedBytes += group.geometry.retainedBytes();
    }

    static void clearCaches() {
        for (ArrayDeque<GeometryGroup> pool : FREE.values()) {
            for (GeometryGroup group : pool) group.geometry.close();
        }
        FREE.clear();
        retainedBytes = 0;
    }

    private void drawBufferedCall(TransparentCall call) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(call.pose());
        poseStack.last().normal().set(call.normal());
        GltfRenderer.renderBufferedPrimitive(call.model(), call.state(), call.primitive(), call.transform(),
                poseStack, call.buffers(), call.packedLight(), call.packedOverlay(), shadowPass, call.pbr());
    }

    private static final class GeometryGroup {
        private final Geometry geometry;
        private final List<GltfGpuBuffer> buffers = new ArrayList<>();

        private GeometryGroup(VertexFormat format) {
            geometry = new Geometry(format);
        }

        private void add(GltfPrimitive primitive, GltfDeformedGeometry deformedGeometry,
                         Matrix4f transform, int packedLight, int packedOverlay, boolean pbr) {
            int requiredVertices = primitive.positions().length / 3;
            if (!geometry.canFit(requiredVertices)) {
                buffers.add(geometry.upload(true));
            }
            geometry.add(primitive, deformedGeometry, transform, packedLight, packedOverlay, pbr);
        }

        private void draw(RenderType renderType) {
            if (!geometry.isEmpty()) {
                buffers.add(geometry.upload(false));
            }
            renderType.setupRenderState();
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) {
                    throw new IllegalStateException("The active glTF compatibility RenderType has no shader | Support: "
                            + GltfRendererApi.SUPPORT_URL);
                }
                for (GltfGpuBuffer buffer : buffers) {
                    buffer.drawWithShader(RenderSystem.getModelViewMatrix(),
                            RenderSystem.getProjectionMatrix(), shader);
                }
            } finally {
                renderType.clearRenderState();
                reset();
            }
        }

        private void reset() {
            for (GltfGpuBuffer buffer : buffers) GltfGpuBufferPool.release(buffer.format(), buffer);
            buffers.clear();
            if (geometry.vertices.building()) geometry.vertices.end().release();
            geometry.vertices.discard();
            geometry.indices.clear();
            geometry.vertexCount = 0;
        }
    }

    private static final class Geometry {
        private static final int MAX_VERTICES = 65_536;
        private final BufferBuilder vertices = new BufferBuilder(1536);
        private final VertexFormat format;
        private final IntArrayList indices = new IntArrayList();
        private int vertexCount;
        private int peakBytes;
        private IntBuffer indexData;

        private Geometry(VertexFormat format) {
            this.format = format;
            begin();
        }

        private void begin() {
            vertices.begin(VertexFormat.Mode.TRIANGLES, format);
        }

        private long retainedBytes() {
            return (long) peakBytes + 2_097_152 + (indexData == null ? 0L : (long) indexData.capacity() * 4);
        }

        private void close() {
            if (indexData != null) MemoryUtil.memFree(indexData);
            indexData = null;
        }

        private boolean canFit(int additionalVertices) {
            return vertexCount == 0 || vertexCount + additionalVertices <= MAX_VERTICES;
        }

        private boolean isEmpty() {
            return vertexCount == 0;
        }

        private void add(GltfPrimitive primitive, GltfDeformedGeometry geometry, Matrix4f transform,
                         int packedLight, int packedOverlay, boolean pbr) {
            GltfMaterial material = primitive.material();
            Matrix3f normalTransform = new Matrix3f(transform).invert().transpose();
            float[] positions = geometry.positions();
            float[] normals = geometry.normals();
            float[] colors = primitive.colors();
            float[] uvs = pbr ? primitive.textureCoordinates() : primitive.compatibleUvs();
            Vector3f position = new Vector3f();
            Vector3f normal = new Vector3f();
            int baseVertex = vertexCount;
            int sourceVertexCount = positions.length / 3;
            ByteBuffer packed = GltfShaderPackCompat.isShaderPackInUse() || GltfShaderPackCompat.isRenderingShadowPass()
                    ? null : GltfStreamWriter.indexed(primitive, geometry, transform, normalTransform,
                    packedLight, packedOverlay, pbr);
            if (packed != null) {
                vertices.putBulkData(packed);
            } else for (int index = 0; index < sourceVertexCount; index++) {
                int vectorOffset = index * 3;
                int textureOffset = index * 2;
                int colorOffset = index * 4;
                transform.transformPosition(positions[vectorOffset], positions[vectorOffset + 1],
                        positions[vectorOffset + 2], position);
                normalTransform.transform(normals[vectorOffset], normals[vectorOffset + 1],
                        normals[vectorOffset + 2], normal).normalize();
                float u = uvs[textureOffset];
                float v = uvs[textureOffset + 1];
                vertices.vertex(position.x, position.y, position.z)
                        .color(colors[colorOffset] * (pbr ? 1.0F : material.red()),
                                colors[colorOffset + 1] * (pbr ? 1.0F : material.green()),
                                colors[colorOffset + 2] * (pbr ? 1.0F : material.blue()),
                                colors[colorOffset + 3] * (pbr ? 1.0F : material.alpha()))
                        .uv(u, v)
                        .overlayCoords(packedOverlay)
                        .uv2(material.unlit() && !pbr ? 15728880 : packedLight)
                        .normal(normal.x, normal.y, normal.z);
                if (pbr) {
                    vertices.putFloat(0, primitive.secondaryTextureCoordinates()[textureOffset]);
                    vertices.putFloat(4, primitive.secondaryTextureCoordinates()[textureOffset + 1]);
                    vertices.nextElement();
                }
                vertices.endVertex();
            }
            for (int index : primitive.indices()) {
                indices.add(baseVertex + index);
            }
            vertexCount += sourceVertexCount;
            GltfRenderMetrics.stream(sourceVertexCount, sourceVertexCount);
            peakBytes = Math.max(peakBytes, vertexCount * format.getVertexSize());
        }

        private GltfGpuBuffer upload(boolean restart) {
            BufferBuilder.RenderedBuffer rendered = vertices.end();
            GltfGpuBuffer buffer = GltfGpuBufferPool.acquire(rendered.drawState().format());
            if (indexData == null || indexData.capacity() < indices.size()) {
                if (indexData != null) MemoryUtil.memFree(indexData);
                indexData = MemoryUtil.memAllocInt(indices.size());
            }
            try {
                indexData.clear();
                indexData.put(indices.elements(), 0, indices.size()).flip();
                buffer.stream(rendered, indexData);
            } catch (RuntimeException | LinkageError exception) {
                buffer.close();
                throw exception;
            } finally {
                rendered.release();
            }
            indices.clear();
            vertexCount = 0;
            if (restart) {
                begin();
            }
            return buffer;
        }
    }

    private record TransparentCall(GltfModel model, GltfRenderState state, GltfPrimitive primitive,
                                   Matrix4f transform, Matrix4f pose, Matrix3f normal,
                                   MultiBufferSource buffers, int packedLight, int packedOverlay,
                                   float distanceSquared, boolean pbr) {
    }

    private record StateKey(GltfModel model, String animation, int animationTimeBits, String scene,
                            GltfNodeRotationOffsets rotationOffsets, long rotationRevision) {
    }
}
