package me.danialisntcool.gltfapi.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Objects;

@OnlyIn(Dist.CLIENT)
public record GltfRenderRequest(GltfModelHandle model, GltfRenderContext context) {
    public GltfRenderRequest {
        Objects.requireNonNull(model);
        Objects.requireNonNull(context);
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(context.poseStack().last().pose());
        poseStack.last().normal().set(context.poseStack().last().normal());
        GltfRenderOptions options = context.options();
        GltfNodeRotationOffsets rotations = options.nodeRotationOffsets().snapshot();
        if (rotations != options.nodeRotationOffsets()) options = options.withNodeRotationOffsets(rotations);
        GltfRenderContext snapshot = new GltfRenderContext(poseStack, context.buffers(),
                context.packedLight(), context.packedOverlay(), options).withRenderMode(context.renderMode());
        Matrix4f modelToWorld = context.modelToWorld();
        if (context.frustum() != null && modelToWorld != null) {
            snapshot = snapshot.withCulling(context.frustum(), modelToWorld);
        }
        context = snapshot;
    }
}
