package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.api.client.GltfNodeRotationOffsets;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class GltfPoseCacheTest {
    private GltfModel model() {
        GltfNode node = new GltfNode("Root", -1, new int[0], -1, -1, null,
                new Vector3f(), new Quaternionf(), new Vector3f(1), new float[0]);
        GltfAnimation animation = new GltfAnimation("Move", 10, List.of(new GltfAnimation.Channel(
                0, GltfAnimation.Path.TRANSLATION, 3, GltfAnimation.Interpolation.LINEAR,
                new float[]{0, 10}, new float[]{0, 0, 0, 10, 0, 0})));
        return new GltfModel(new ResourceLocation("test", "model"), List.of(), Map.of(), List.of(node),
                List.of(new GltfScene("Scene", new int[]{0})), 0, List.of(animation), List.of());
    }

    @Test
    void sharesExactAnimationStatesWithoutQuantizingTime() {
        GltfModel model = model();
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets();
        GltfRenderState first = model.renderState("Move", 2, null, offsets);
        assertSame(first, model.renderState("Move", 2, null, new GltfNodeRotationOffsets()));
        assertNotSame(first, model.renderState("Move", 2.001F, null, offsets));
        assertEquals(2, first.transforms()[0].m30(), 0.00001F);
    }

    @Test
    void invalidatesAfterLiveNodeChangesAndEvictsOldPoses() {
        GltfModel model = model();
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets().set("Root", new Quaternionf());
        GltfRenderState first = model.renderState("Move", 2, null, offsets);
        offsets.set("Root", new Quaternionf().rotateY(1));
        assertNotSame(first, model.renderState("Move", 2, null, offsets));
        GltfRenderState recent = model.renderState("Move", 3, null, offsets);
        for (int index = 0; index <= ModMetadata.POSE_CACHE_ENTRIES; index++) {
            model.renderState("Move", 20 + index, null, offsets);
        }
        assertNotSame(recent, model.renderState("Move", 3, null, offsets));
        model.close();
        assertNotSame(first, model.renderState("Move", 2, null, offsets));
    }
}
