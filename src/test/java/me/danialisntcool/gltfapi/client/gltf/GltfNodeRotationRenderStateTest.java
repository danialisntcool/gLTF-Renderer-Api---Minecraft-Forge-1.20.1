package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.api.client.GltfNodeRotationOffsets;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GltfNodeRotationRenderStateTest {
    @Test
    void composesLiveLocalRotationAfterAnimationRotation() {
        GltfNode node = new GltfNode("Head", -1, new int[0], -1, -1, null,
                new Vector3f(), new Quaternionf(), new Vector3f(1.0F), new float[0]);
        Quaternionf animated = new Quaternionf().rotateX(0.4F);
        GltfAnimation.Channel channel = new GltfAnimation.Channel(0, GltfAnimation.Path.ROTATION, 4,
                GltfAnimation.Interpolation.LINEAR, new float[]{0.0F},
                new float[]{animated.x, animated.y, animated.z, animated.w});
        GltfAnimation animation = new GltfAnimation("Look", 0.0F, List.of(channel));
        GltfModel model = new GltfModel(
                ResourceLocation.fromNamespaceAndPath("test", "models/gltf/test.gltf"),
                List.of(), Map.of(), List.of(node), List.of(new GltfScene("Scene", new int[]{0})),
                0, List.of(animation), List.of());
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets()
                .set("Head", new Quaternionf().rotateY(0.6F));

        GltfRenderState state = model.renderState("Look", 0.0F, null, offsets);
        Matrix4f expected = new Matrix4f().rotate(animated).rotateY(0.6F);
        assertMatrix(expected, state.transforms()[0]);

        offsets.set("Head", new Quaternionf().rotateZ(0.8F));
        GltfRenderState updated = model.renderState("Look", 0.0F, null, offsets);
        assertMatrix(new Matrix4f().rotate(animated).rotateZ(0.8F), updated.transforms()[0]);
        assertEquals(List.of("Head"), model.nodeNames());
    }

    private static void assertMatrix(Matrix4f expected, Matrix4f actual) {
        float[] expectedValues = expected.get(new float[16]);
        float[] actualValues = actual.get(new float[16]);
        for (int index = 0; index < expectedValues.length; index++) {
            assertEquals(expectedValues[index], actualValues[index], 0.00001F);
        }
    }
}
