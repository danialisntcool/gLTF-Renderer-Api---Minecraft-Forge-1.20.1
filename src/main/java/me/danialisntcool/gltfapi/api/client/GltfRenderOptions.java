package me.danialisntcool.gltfapi.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record GltfRenderOptions(
        Vector3f translation,
        Quaternionf rotation,
        Vector3f scale,
        String animation,
        float animationTimeSeconds,
        String scene,
        GltfNodeRotationOffsets nodeRotationOffsets
) {
    public static final GltfRenderOptions DEFAULT = new GltfRenderOptions(
            new Vector3f(),
            new Quaternionf(),
            new Vector3f(1.0F),
            null,
            0.0F,
            null,
            GltfNodeRotationOffsets.empty());

    public GltfRenderOptions(Vector3f translation, Quaternionf rotation, Vector3f scale,
                             String animation, float animationTimeSeconds, String scene) {
        this(translation, rotation, scale, animation, animationTimeSeconds, scene,
                GltfNodeRotationOffsets.empty());
    }

    public GltfRenderOptions(Vector3f translation, Quaternionf rotation, Vector3f scale,
                             String animation, float animationTimeSeconds) {
        this(translation, rotation, scale, animation, animationTimeSeconds, null);
    }

    public GltfRenderOptions {
        translation = new Vector3f(translation);
        rotation = new Quaternionf(rotation);
        scale = new Vector3f(scale);
        nodeRotationOffsets = java.util.Objects.requireNonNull(nodeRotationOffsets);
    }

    @Override
    public Vector3f translation() {
        return new Vector3f(translation);
    }

    @Override
    public Quaternionf rotation() {
        return new Quaternionf(rotation);
    }

    @Override
    public Vector3f scale() {
        return new Vector3f(scale);
    }

    public GltfRenderOptions withAnimation(String animation, float timeSeconds) {
        return new GltfRenderOptions(translation, rotation, scale, animation, timeSeconds, scene, nodeRotationOffsets);
    }

    public GltfRenderOptions withTransform(Vector3f translation, Quaternionf rotation, Vector3f scale) {
        return new GltfRenderOptions(translation, rotation, scale, animation, animationTimeSeconds, scene,
                nodeRotationOffsets);
    }

    public GltfRenderOptions withScene(String scene) {
        return new GltfRenderOptions(translation, rotation, scale, animation, animationTimeSeconds, scene,
                nodeRotationOffsets);
    }

    public GltfRenderOptions withNodeRotationOffsets(GltfNodeRotationOffsets nodeRotationOffsets) {
        return new GltfRenderOptions(translation, rotation, scale, animation, animationTimeSeconds, scene,
                nodeRotationOffsets);
    }

    void apply(PoseStack poseStack) {
        poseStack.translate(translation.x, translation.y, translation.z);
        poseStack.mulPose(rotation);
        poseStack.scale(scale.x, scale.y, scale.z);
    }

    public Matrix4f transformationMatrix() {
        return new Matrix4f().translation(translation).rotate(rotation).scale(scale);
    }
}
