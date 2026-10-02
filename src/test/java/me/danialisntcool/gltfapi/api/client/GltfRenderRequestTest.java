package me.danialisntcool.gltfapi.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GltfRenderRequestTest {
    @Test
    void queuedNodeOffsetsAreImmutableAndSharedUntilChanged() {
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets().set("Head", new org.joml.Quaternionf().rotateY(0.6F));
        var snapshot = offsets.snapshot();
        assertSame(snapshot, offsets.snapshot());
        var request = new GltfRenderRequest(new GltfModelHandle(new ResourceLocation("test", "queued")),
                new GltfRenderContext(new PoseStack(), type -> null, 240,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                        GltfRenderOptions.DEFAULT.withNodeRotationOffsets(offsets)));
        offsets.set("Head", new org.joml.Quaternionf().rotateY(1.2F));
        org.joml.Quaternionf actual = new org.joml.Quaternionf();
        assertTrue(request.context().options().nodeRotationOffsets().resolve(0, "Head", actual));
        assertTrue(new org.joml.Quaternionf().rotateY(0.6F).equals(actual, 0.00001F));
        assertNotSame(snapshot, offsets.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    }

    @Test
    void snapshotsPoseAndRetainsExplicitRenderMode() {
        PoseStack pose = new PoseStack();
        pose.pushPose();
        pose.translate(4, 5, 6);
        GltfRenderContext context = GltfRenderContext.create(pose, type -> null, 240)
                .withRenderMode(GltfRenderMode.NATIVE);
        GltfRenderRequest request = new GltfRenderRequest(
                new GltfModelHandle(ResourceLocation.fromNamespaceAndPath("test", "model")), context);
        pose.popPose();
        assertEquals(4, request.context().poseStack().last().pose().m30());
        assertEquals(5, request.context().poseStack().last().pose().m31());
        assertEquals(6, request.context().poseStack().last().pose().m32());
        assertEquals(GltfRenderMode.NATIVE, request.context().renderMode());
        assertNotSame(pose, request.context().poseStack());
    }
}
