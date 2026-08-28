package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.danialisntcool.gltfapi.GltfRendererApi;
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

public final class GltfCompatibleBatch {
    private final boolean shadowPass;
    private final Map<RenderType, GeometryGroup> groups = new LinkedHashMap<>();
    private final Map<StateKey, GltfRenderState> states = new LinkedHashMap<>();
    private final List<TransparentCall> transparent = new ArrayList<>();

    public GltfCompatibleBatch(boolean shadowPass) {
        this.shadowPass = shadowPass;
    }

    public void add(GltfModel model, Matrix4f pose, Matrix3f normal,
                    MultiBufferSource buffers, int packedLight, int packedOverlay,
                    me.danialisntcool.gltfapi.api.client.GltfRenderOptions options) {
        StateKey stateKey = new StateKey(model, options.animation(),
                Float.floatToIntBits(options.animationTimeSeconds()), options.scene());
        GltfRenderState state = states.computeIfAbsent(stateKey, ignored -> model.renderState(
                options.animation(), options.animationTimeSeconds(), options.scene()));
        for (GltfPrimitive primitive : model.primitives()) {
            if (!state.visibleNodes().get(primitive.nodeIndex())) {
                continue;
            }
            Matrix4f nodeTransform = state.transforms()[primitive.nodeIndex()];
            GltfDeformedGeometry geometry = GltfRenderer.deformedGeometry(model, state, primitive);
            for (Matrix4f instance : primitive.instances()) {
                Matrix4f localTransform = new Matrix4f(nodeTransform).mul(instance);
                if (primitive.material().alphaMode() == GltfMaterial.AlphaMode.BLEND && !shadowPass) {
                    Vector3f center = GltfRenderer.primitiveCenter(primitive, localTransform, pose);
                    transparent.add(new TransparentCall(model, state, primitive, localTransform,
                            new Matrix4f(pose), new Matrix3f(normal), buffers,
                            packedLight, packedOverlay, center.lengthSquared()));
                    continue;
                }
                RenderType renderType = GltfRenderer.compatibleRenderType(primitive.material(), shadowPass);
                groups.computeIfAbsent(renderType, ignored -> new GeometryGroup())
                        .add(primitive, geometry, new Matrix4f(pose).mul(localTransform),
                                packedLight, packedOverlay);
            }
        }
    }

    public void flush() {
        RenderSystem.assertOnRenderThread();
        for (Map.Entry<RenderType, GeometryGroup> entry : groups.entrySet()) {
            entry.getValue().draw(entry.getKey());
        }
        transparent.sort(Comparator.comparingDouble(TransparentCall::distanceSquared).reversed());
        for (TransparentCall call : transparent) {
            PoseStack poseStack = new PoseStack();
            poseStack.last().pose().set(call.pose());
            poseStack.last().normal().set(call.normal());
            GltfRenderer.renderCompatible(call.model(), call.state(), call.primitive(), call.transform(),
                    poseStack, call.buffers(), call.packedLight(), call.packedOverlay(), false);
        }
        groups.clear();
        states.clear();
        transparent.clear();
    }

    private static final class GeometryGroup {
        private final Geometry geometry = new Geometry();
        private final List<GltfGpuBuffer> buffers = new ArrayList<>();

        private void add(GltfPrimitive primitive, GltfDeformedGeometry deformedGeometry,
                         Matrix4f transform, int packedLight, int packedOverlay) {
            int requiredVertices = primitive.positions().length / 3;
            if (!geometry.canFit(requiredVertices)) {
                buffers.add(geometry.upload(true));
            }
            geometry.add(primitive, deformedGeometry, transform, packedLight, packedOverlay);
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
                for (GltfGpuBuffer buffer : buffers) {
                    buffer.close();
                }
                buffers.clear();
            }
        }
    }

    private static final class Geometry {
        private static final int MAX_VERTICES = 65_536;
        private final BufferBuilder vertices = new BufferBuilder(1_048_576);
        private final IntArrayList indices = new IntArrayList();
        private int vertexCount;

        private Geometry() {
            vertices.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        }

        private boolean canFit(int additionalVertices) {
            return vertexCount == 0 || vertexCount + additionalVertices <= MAX_VERTICES;
        }

        private boolean isEmpty() {
            return vertexCount == 0;
        }

        private void add(GltfPrimitive primitive, GltfDeformedGeometry geometry, Matrix4f transform,
                         int packedLight, int packedOverlay) {
            GltfMaterial material = primitive.material();
            Matrix3f normalTransform = new Matrix3f(transform).invert().transpose();
            float[] positions = geometry.positions();
            float[] normals = geometry.normals();
            float[] colors = primitive.colors();
            float[] primaryUvs = primitive.textureCoordinates();
            float[] secondaryUvs = primitive.secondaryTextureCoordinates();
            GltfTextureInfo texture = material.baseColorTexture();
            Vector3f position = new Vector3f();
            Vector3f normal = new Vector3f();
            int baseVertex = vertexCount;
            int sourceVertexCount = positions.length / 3;
            for (int index = 0; index < sourceVertexCount; index++) {
                int vectorOffset = index * 3;
                int textureOffset = index * 2;
                int colorOffset = index * 4;
                transform.transformPosition(positions[vectorOffset], positions[vectorOffset + 1],
                        positions[vectorOffset + 2], position);
                normalTransform.transform(normals[vectorOffset], normals[vectorOffset + 1],
                        normals[vectorOffset + 2], normal).normalize();
                float[] uvs = texture != null && texture.textureCoordinate() == 1 ? secondaryUvs : primaryUvs;
                float u = uvs[textureOffset];
                float v = uvs[textureOffset + 1];
                if (texture != null) {
                    float cosine = (float) Math.cos(texture.rotation());
                    float sine = (float) Math.sin(texture.rotation());
                    float rotatedU = u * cosine - v * sine;
                    float rotatedV = u * sine + v * cosine;
                    u = texture.offsetX() + rotatedU * texture.scaleX();
                    v = texture.offsetY() + rotatedV * texture.scaleY();
                }
                vertices.vertex(position.x, position.y, position.z)
                        .color(colors[colorOffset] * material.red(),
                                colors[colorOffset + 1] * material.green(),
                                colors[colorOffset + 2] * material.blue(),
                                colors[colorOffset + 3] * material.alpha())
                        .uv(u, v)
                        .overlayCoords(packedOverlay)
                        .uv2(material.unlit() ? 15728880 : packedLight)
                        .normal(normal.x, normal.y, normal.z)
                        .endVertex();
            }
            for (int index : primitive.indices()) {
                indices.add(baseVertex + index);
            }
            vertexCount += sourceVertexCount;
        }

        private GltfGpuBuffer upload(boolean restart) {
            BufferBuilder.RenderedBuffer rendered = vertices.end();
            GltfGpuBuffer buffer;
            try {
                buffer = new GltfGpuBuffer(rendered.drawState().format(), rendered, indices.toIntArray());
            } finally {
                rendered.release();
            }
            indices.clear();
            vertexCount = 0;
            if (restart) {
                vertices.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            }
            return buffer;
        }
    }

    private record TransparentCall(GltfModel model, GltfRenderState state, GltfPrimitive primitive,
                                   Matrix4f transform, Matrix4f pose, Matrix3f normal,
                                   MultiBufferSource buffers, int packedLight, int packedOverlay,
                                   float distanceSquared) {
    }

    private record StateKey(GltfModel model, String animation, int animationTimeBits, String scene) {
    }
}
