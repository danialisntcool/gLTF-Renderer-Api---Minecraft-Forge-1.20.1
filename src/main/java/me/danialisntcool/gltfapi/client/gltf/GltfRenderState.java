package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix4f;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;

final class GltfRenderState {
    private final Matrix4f[] transforms;
    private final BitSet visibleNodes;
    private final float[][] morphWeights;
    private final Map<GltfPrimitive, float[]> skinMatrices = new IdentityHashMap<>();
    private final Map<GltfPrimitive, GltfDeformedGeometry> deformedGeometry = new IdentityHashMap<>();

    GltfRenderState(Matrix4f[] transforms, BitSet visibleNodes, float[][] morphWeights) {
        this.transforms = transforms;
        this.visibleNodes = visibleNodes;
        this.morphWeights = morphWeights;
    }

    Matrix4f[] transforms() {
        return transforms;
    }

    BitSet visibleNodes() {
        return visibleNodes;
    }

    float[][] morphWeights() {
        return morphWeights;
    }

    float[] cachedSkinMatrices(GltfPrimitive primitive) {
        return skinMatrices.get(primitive);
    }

    void cacheSkinMatrices(GltfPrimitive primitive, float[] matrices) {
        skinMatrices.put(primitive, matrices);
    }

    GltfDeformedGeometry cachedDeformedGeometry(GltfPrimitive primitive) {
        return deformedGeometry.get(primitive);
    }

    void cacheDeformedGeometry(GltfPrimitive primitive, GltfDeformedGeometry geometry) {
        deformedGeometry.put(primitive, geometry);
    }
}
