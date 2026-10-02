package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.GltfRendererApi;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.danialisntcool.gltfapi.client.render.GltfVertexFormats;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Arrays;
import java.util.BitSet;
import me.danialisntcool.gltfapi.api.client.GltfBounds;

public final class GltfPrimitive implements AutoCloseable {
    private final float[] positions;
    private final float[] normals;
    private final float[] textureCoordinates;
    private final float[] secondaryTextureCoordinates;
    private final float[] colors;
    private final float[] joints;
    private final float[] weights;
    private final float[][] morphPositions;
    private final float[][] morphNormals;
    private final int[] indices;
    private final GltfMaterial material;
    private final Matrix4f transform;
    private final int nodeIndex;
    private final int skinIndex;
    private final List<Matrix4f> instances;
    private final Vector3f center;
    private final Vector3f minimum;
    private final Vector3f maximum;
    private final Vector3f[] morphMinimum;
    private final Vector3f[] morphMaximum;
    private final BitSet activeJoints = new BitSet(64);
    private GltfGpuBuffer vertexBuffer;
    private int[] sortedIndices;
    private final float[] compatibleUvs;
    private GltfTriangleSorter triangleSorter;

    public GltfPrimitive(float[] positions, float[] normals, float[] textureCoordinates,
                         float[] secondaryTextureCoordinates, float[] colors,
                         float[] joints, float[] weights, float[][] morphPositions, float[][] morphNormals,
                         int[] indices, GltfMaterial material,
                         Matrix4f transform, int nodeIndex, int skinIndex,
                         List<Matrix4f> instances) {
        this.positions = positions;
        this.normals = normals;
        this.textureCoordinates = textureCoordinates;
        this.secondaryTextureCoordinates = secondaryTextureCoordinates;
        this.colors = colors;
        this.joints = joints;
        this.weights = weights;
        this.morphPositions = morphPositions;
        this.morphNormals = morphNormals;
        this.indices = indices;
        this.material = material;
        this.transform = new Matrix4f(transform);
        this.nodeIndex = nodeIndex;
        this.skinIndex = skinIndex;
        this.instances = instances.stream().map(Matrix4f::new).toList();
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f vertex = new Vector3f();
        for (int offset = 0; offset < positions.length; offset += 3) {
            vertex.set(positions[offset], positions[offset + 1], positions[offset + 2]);
            minimum.min(vertex);
            maximum.max(vertex);
        }
        if (positions.length == 0) {
            minimum.zero();
            maximum.zero();
        }
        this.minimum = new Vector3f(minimum);
        this.maximum = new Vector3f(maximum);
        this.center = minimum.add(maximum).mul(0.5F);
        morphMinimum = new Vector3f[morphPositions.length];
        morphMaximum = new Vector3f[morphPositions.length];
        for (int target = 0; target < morphPositions.length; target++) {
            Vector3f min = new Vector3f(0);
            Vector3f max = new Vector3f(0);
            for (int offset = 0; offset < morphPositions[target].length; offset += 3) {
                vertex.set(morphPositions[target][offset], morphPositions[target][offset + 1], morphPositions[target][offset + 2]);
                min.min(vertex);
                max.max(vertex);
            }
            morphMinimum[target] = min;
            morphMaximum[target] = max;
        }
        for (int influence = 0; weights != null && influence < weights.length; influence++) {
            if (weights[influence] > 0) activeJoints.set((int) joints[influence]);
        }
        GltfTextureInfo texture = material.baseColorTexture();
        this.compatibleUvs = texture == null ? textureCoordinates
                : texture.transformedCoordinates(texture.textureCoordinate() == 1
                ? secondaryTextureCoordinates : textureCoordinates);
    }

    public float[] positions() {
        return positions;
    }

    public float[] normals() {
        return normals;
    }

    public float[] textureCoordinates() {
        return textureCoordinates;
    }

    public float[] colors() {
        return colors;
    }

    public float[] secondaryTextureCoordinates() {
        return secondaryTextureCoordinates;
    }

    float[] compatibleUvs() {
        return compatibleUvs;
    }

    int[] sortedIndices(Matrix4f pose, float[] deformedPositions) {
        if (triangleSorter == null) {
            triangleSorter = new GltfTriangleSorter(indices);
        }
        return triangleSorter.sort(pose, deformedPositions);
    }

    public int[] indices() {
        return indices;
    }

    public GltfMaterial material() {
        return material;
    }

    public Matrix4f transform() {
        return new Matrix4f(transform);
    }

    int nodeIndex() {
        return nodeIndex;
    }

    int skinIndex() {
        return skinIndex;
    }

    int morphTargetCount() {
        return morphPositions.length;
    }

    float[] joints() {
        return joints;
    }

    float[] weights() {
        return weights;
    }

    float[][] morphPositions() {
        return morphPositions;
    }

    float[][] morphNormals() {
        return morphNormals;
    }

    GltfBounds morphedBounds(float[] weights) {
        Vector3f min = new Vector3f(minimum);
        Vector3f max = new Vector3f(maximum);
        for (int target = 0; target < Math.min(weights.length, morphMinimum.length); target++) {
            float weight = weights[target];
            min.fma(weight, weight >= 0 ? morphMinimum[target] : morphMaximum[target]);
            max.fma(weight, weight >= 0 ? morphMaximum[target] : morphMinimum[target]);
        }
        return new GltfBounds(min, max);
    }

    BitSet activeJoints() {
        return activeJoints;
    }

    List<Matrix4f> instances() {
        return instances;
    }

    Vector3f center() {
        return new Vector3f(center);
    }

    void upload() {
        RenderSystem.assertOnRenderThreadOrInit();
        close();
        vertexBuffer = buildVertexBuffer(indices);
    }

    private GltfGpuBuffer buildVertexBuffer(int[] orderedIndices) {
        int vertexSize = morphPositions.length > 0 ? 164 : skinIndex >= 0 ? 68 : 36;
        int vertexCount = positions.length / 3;
        BufferBuilder builder = new BufferBuilder(Math.max(vertexCount * vertexSize, 256));
        builder.begin(VertexFormat.Mode.TRIANGLES,
                morphPositions.length > 0 ? GltfVertexFormats.MORPHED_MODEL
                        : skinIndex >= 0 ? GltfVertexFormats.SKINNED_MODEL : GltfVertexFormats.MODEL);
        for (int index = 0; index < vertexCount; index++) {
            int positionOffset = index * 3;
            int textureOffset = index * 2;
            int colorOffset = index * 4;
            builder.vertex(positions[positionOffset], positions[positionOffset + 1], positions[positionOffset + 2])
                    .uv(textureCoordinates[textureOffset], textureCoordinates[textureOffset + 1]);
            builder.putFloat(0, secondaryTextureCoordinates[textureOffset]);
            builder.putFloat(4, secondaryTextureCoordinates[textureOffset + 1]);
            builder.nextElement();
            builder.color(
                            colors[colorOffset],
                            colors[colorOffset + 1],
                            colors[colorOffset + 2],
                            colors[colorOffset + 3])
                    .normal(normals[positionOffset], normals[positionOffset + 1], normals[positionOffset + 2]);
            if (skinIndex >= 0 || morphPositions.length > 0) {
                int jointOffset = index * 4;
                builder.putFloat(0, skinIndex >= 0 ? joints[jointOffset] : 0.0F);
                builder.putFloat(4, skinIndex >= 0 ? joints[jointOffset + 1] : 0.0F);
                builder.putFloat(8, skinIndex >= 0 ? joints[jointOffset + 2] : 0.0F);
                builder.putFloat(12, skinIndex >= 0 ? joints[jointOffset + 3] : 0.0F);
                builder.nextElement();
                builder.putFloat(0, skinIndex >= 0 ? weights[jointOffset] : 0.0F);
                builder.putFloat(4, skinIndex >= 0 ? weights[jointOffset + 1] : 0.0F);
                builder.putFloat(8, skinIndex >= 0 ? weights[jointOffset + 2] : 0.0F);
                builder.putFloat(12, skinIndex >= 0 ? weights[jointOffset + 3] : 0.0F);
                builder.nextElement();
            }
            if (morphPositions.length > 0) {
                for (int target = 0; target < 4; target++) {
                    putVector(builder, target < morphPositions.length ? morphPositions[target] : null, positionOffset);
                }
                for (int target = 0; target < 4; target++) {
                    putVector(builder, target < morphNormals.length ? morphNormals[target] : null, positionOffset);
                }
            }
            builder.endVertex();
        }
        BufferBuilder.RenderedBuffer rendered = builder.end();
        try {
            return new GltfGpuBuffer(rendered.drawState().format(), rendered, orderedIndices);
        } finally {
            rendered.release();
        }
    }

    private void putVector(BufferBuilder builder, float[] values, int offset) {
        builder.putFloat(0, values == null ? 0.0F : values[offset]);
        builder.putFloat(4, values == null ? 0.0F : values[offset + 1]);
        builder.putFloat(8, values == null ? 0.0F : values[offset + 2]);
        builder.nextElement();
    }

    GltfGpuBuffer vertexBuffer() {
        if (vertexBuffer == null || vertexBuffer.isInvalid()) {
            throw new IllegalStateException("glTF primitive has not been uploaded to the GPU | Support: "
                    + GltfRendererApi.SUPPORT_URL);
        }
        return vertexBuffer;
    }

    GltfGpuBuffer sortedVertexBuffer(int[] orderedIndices) {
        if (!Arrays.equals(sortedIndices, orderedIndices)) {
            vertexBuffer.updateIndices(orderedIndices);
            if (sortedIndices == null || sortedIndices.length != orderedIndices.length) {
                sortedIndices = new int[orderedIndices.length];
            }
            System.arraycopy(orderedIndices, 0, sortedIndices, 0, orderedIndices.length);
        }
        return vertexBuffer;
    }

    @Override
    public void close() {
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
        sortedIndices = null;
        triangleSorter = null;
    }
}
