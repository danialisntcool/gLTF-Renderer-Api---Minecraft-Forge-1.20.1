package me.danialisntcool.gltfapi.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.Objects;

@OnlyIn(Dist.CLIENT)
public final class GltfRenderContext {
    private final PoseStack poseStack;
    private final MultiBufferSource buffers;
    private final int packedLight;
    private final int packedOverlay;
    private final GltfRenderOptions options;
    private final Frustum frustum;
    private final Matrix4f modelToWorld;

    public GltfRenderContext(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                             int packedOverlay, GltfRenderOptions options) {
        this(poseStack, buffers, packedLight, packedOverlay, options, null, null);
    }

    private GltfRenderContext(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                              int packedOverlay, GltfRenderOptions options,
                              Frustum frustum, Matrix4f modelToWorld) {
        this.poseStack = Objects.requireNonNull(poseStack);
        this.buffers = Objects.requireNonNull(buffers);
        this.packedLight = packedLight;
        this.packedOverlay = packedOverlay;
        this.options = Objects.requireNonNull(options);
        this.frustum = frustum;
        this.modelToWorld = modelToWorld == null ? null : new Matrix4f(modelToWorld);
    }

    public static GltfRenderContext create(PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        return new GltfRenderContext(poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY, GltfRenderOptions.DEFAULT);
    }

    public static GltfRenderContext create(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                           int packedOverlay) {
        return new GltfRenderContext(poseStack, buffers, packedLight, packedOverlay, GltfRenderOptions.DEFAULT);
    }

    public GltfRenderContext withOptions(GltfRenderOptions options) {
        return new GltfRenderContext(poseStack, buffers, packedLight, packedOverlay,
                options, frustum, modelToWorld);
    }

    public GltfRenderContext withCulling(Frustum frustum, Matrix4f modelToWorld) {
        return new GltfRenderContext(poseStack, buffers, packedLight, packedOverlay,
                options, Objects.requireNonNull(frustum), Objects.requireNonNull(modelToWorld));
    }

    public PoseStack poseStack() {
        return poseStack;
    }

    public MultiBufferSource buffers() {
        return buffers;
    }

    public int packedLight() {
        return packedLight;
    }

    public int packedOverlay() {
        return packedOverlay;
    }

    public GltfRenderOptions options() {
        return options;
    }

    public Frustum frustum() {
        return frustum;
    }

    public Matrix4f modelToWorld() {
        return modelToWorld == null ? null : new Matrix4f(modelToWorld);
    }
}
