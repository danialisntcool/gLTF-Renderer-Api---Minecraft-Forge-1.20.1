package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.api.client.GltfModelStatistics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.BitSet;
import java.util.HashMap;

public final class GltfModel implements AutoCloseable {
    private final ResourceLocation location;
    private final List<GltfPrimitive> primitives;
    private final Map<ResourceLocation, GltfEmbeddedTexture> embeddedTextures;
    private final GltfModelStatistics statistics;
    private final List<GltfNode> nodes;
    private final List<GltfScene> scenes;
    private final int defaultScene;
    private final List<GltfAnimation> animations;
    private final List<GltfSkin> skins;
    private final Map<String, GltfRenderState> staticRenderStates = new HashMap<>();
    private final boolean hasBlendedPrimitives;

    public GltfModel(ResourceLocation location, List<GltfPrimitive> primitives,
                     Map<ResourceLocation, GltfEmbeddedTexture> embeddedTextures, List<GltfNode> nodes,
                     List<GltfScene> scenes, int defaultScene, List<GltfAnimation> animations,
                     List<GltfSkin> skins) {
        this.location = location;
        this.primitives = List.copyOf(primitives);
        this.embeddedTextures = Map.copyOf(embeddedTextures);
        this.nodes = List.copyOf(nodes);
        this.scenes = List.copyOf(scenes);
        this.defaultScene = defaultScene;
        this.animations = List.copyOf(animations);
        this.skins = List.copyOf(skins);
        this.hasBlendedPrimitives = primitives.stream()
                .anyMatch(primitive -> primitive.material().alphaMode() == GltfMaterial.AlphaMode.BLEND);
        this.statistics = calculateStatistics();
    }

    public ResourceLocation location() {
        return location;
    }

    public List<GltfPrimitive> primitives() {
        return primitives;
    }

    public GltfModelStatistics statistics() {
        return statistics;
    }

    public List<String> animationNames() {
        return animations.stream().map(GltfAnimation::name).toList();
    }

    public List<String> sceneNames() {
        return scenes.stream().map(GltfScene::name).toList();
    }

    GltfRenderState renderState(String animationName, float animationTime, String sceneName) {
        if (animationName == null) {
            return staticRenderStates.computeIfAbsent(sceneName == null ? "" : sceneName,
                    key -> calculateRenderState(null, 0.0F, sceneName));
        }
        return calculateRenderState(animationName, animationTime, sceneName);
    }

    private GltfRenderState calculateRenderState(String animationName, float animationTime, String sceneName) {
        GltfNode.NodePose[] poses = new GltfNode.NodePose[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            poses[index] = nodes.get(index).pose();
        }
        if (animationName != null) {
            GltfAnimation animation = animations.stream()
                    .filter(candidate -> candidate.name().equals(animationName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown glTF animation '" + animationName + "' in " + location));
            animation.apply(animationTime, poses);
        }
        Matrix4f[] transforms = new Matrix4f[nodes.size()];
        boolean[] active = new boolean[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            worldTransform(index, poses, transforms, active);
        }
        int sceneIndex = defaultScene;
        if (sceneName != null) {
            sceneIndex = -1;
            for (int index = 0; index < scenes.size(); index++) {
                if (scenes.get(index).name().equals(sceneName)) {
                    sceneIndex = index;
                    break;
                }
            }
            if (sceneIndex < 0) {
                throw new IllegalArgumentException("Unknown glTF scene '" + sceneName + "' in " + location);
            }
        }
        BitSet visible = new BitSet(nodes.size());
        for (int root : scenes.get(sceneIndex).roots()) {
            markVisible(root, visible);
        }
        float[][] morphWeights = new float[poses.length][];
        for (int index = 0; index < poses.length; index++) {
            morphWeights[index] = java.util.Arrays.copyOf(poses[index].weights(), poses[index].weights().length);
        }
        return new GltfRenderState(transforms, visible, morphWeights);
    }

    float[] skinMatrices(GltfPrimitive primitive, GltfRenderState state) {
        float[] cached = state.cachedSkinMatrices(primitive);
        if (cached != null) {
            return cached;
        }
        GltfSkin skin = skins.get(primitive.skinIndex());
        Matrix4f inverseMeshTransform = new Matrix4f(state.transforms()[primitive.nodeIndex()]).invert();
        float[] result = new float[64 * 16];
        for (int matrixIndex = 0; matrixIndex < 64; matrixIndex++) {
            result[matrixIndex * 16] = 1.0F;
            result[matrixIndex * 16 + 5] = 1.0F;
            result[matrixIndex * 16 + 10] = 1.0F;
            result[matrixIndex * 16 + 15] = 1.0F;
        }
        for (int jointIndex = 0; jointIndex < skin.joints().length; jointIndex++) {
            Matrix4f matrix = new Matrix4f(inverseMeshTransform)
                    .mul(state.transforms()[skin.joints()[jointIndex]])
                    .mul(skin.inverseBindMatrices().get(jointIndex));
            matrix.get(result, jointIndex * 16);
        }
        state.cacheSkinMatrices(primitive, result);
        return result;
    }

    boolean hasBlendedPrimitives() {
        return hasBlendedPrimitives;
    }

    Map<ResourceLocation, GltfEmbeddedTexture> embeddedTextures() {
        return embeddedTextures;
    }

    void upload() {
        for (GltfPrimitive primitive : primitives) {
            primitive.upload();
        }
    }

    @Override
    public void close() {
        for (GltfPrimitive primitive : primitives) {
            primitive.close();
        }
    }

    private GltfModelStatistics calculateStatistics() {
        int vertices = 0;
        int triangles = 0;
        long gpuBytes = 0L;
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f transformed = new Vector3f();
        for (GltfPrimitive primitive : primitives) {
            float[] positions = primitive.positions();
            Matrix4f transform = primitive.transform();
            vertices += positions.length / 3;
            triangles += primitive.indices().length / 3;
            long vertexSize = primitive.morphTargetCount() > 0 ? 164L : primitive.skinIndex() >= 0 ? 68L : 36L;
            gpuBytes += (long) (primitive.positions().length / 3) * vertexSize
                    + (long) primitive.indices().length * Integer.BYTES;
            for (Matrix4f instance : primitive.instances()) {
                Matrix4f instanceTransform = new Matrix4f(transform).mul(instance);
                for (int offset = 0; offset < positions.length; offset += 3) {
                    instanceTransform.transformPosition(
                            positions[offset], positions[offset + 1], positions[offset + 2], transformed);
                    minimum.min(transformed);
                    maximum.max(transformed);
                }
            }
        }
        if (vertices == 0) {
            minimum.zero();
            maximum.zero();
        }
        return new GltfModelStatistics(primitives.size(), vertices, triangles, gpuBytes, minimum, maximum);
    }

    private Matrix4f worldTransform(int index, GltfNode.NodePose[] poses, Matrix4f[] transforms, boolean[] active) {
        if (transforms[index] != null) {
            return transforms[index];
        }
        if (active[index]) {
            throw new IllegalStateException("Cycle in validated glTF node hierarchy");
        }
        active[index] = true;
        GltfNode node = nodes.get(index);
        Matrix4f local = node.localTransform(poses[index]);
        transforms[index] = node.parent() < 0
                ? local
                : new Matrix4f(worldTransform(node.parent(), poses, transforms, active)).mul(local);
        active[index] = false;
        return transforms[index];
    }

    private void markVisible(int nodeIndex, BitSet visible) {
        if (visible.get(nodeIndex)) {
            return;
        }
        visible.set(nodeIndex);
        for (int child : nodes.get(nodeIndex).children()) {
            markVisible(child, visible);
        }
    }
}
