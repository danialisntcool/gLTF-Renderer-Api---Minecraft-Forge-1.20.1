package me.danialisntcool.gltfapi.api.client;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GltfNodeRotationOffsetsTest {
    @Test
    void resolvesLiveOffsetsWithIndexPrecedence() {
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets();
        Quaternionf destination = new Quaternionf();

        offsets.set("Head", new Quaternionf().rotateY(0.5F));
        long namedRevision = offsets.revision();
        assertTrue(offsets.resolve(4, "Head", destination));
        assertQuaternion(new Quaternionf().rotateY(0.5F), destination);

        offsets.set(4, new Quaternionf().rotateX(0.75F));
        assertTrue(offsets.revision() > namedRevision);
        assertTrue(offsets.resolve(4, "Head", destination));
        assertQuaternion(new Quaternionf().rotateX(0.75F), destination);

        offsets.remove(4);
        assertTrue(offsets.resolve(4, "Head", destination));
        assertQuaternion(new Quaternionf().rotateY(0.5F), destination);

        offsets.clear();
        assertTrue(offsets.isEmpty());
        assertFalse(offsets.resolve(4, "Head", destination));
    }

    @Test
    void normalizesAndValidatesRotations() {
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets();
        Quaternionf destination = new Quaternionf();

        offsets.set(1, new Quaternionf(0.0F, 2.0F, 0.0F, 2.0F));
        assertTrue(offsets.resolve(1, "Node 1", destination));
        assertEquals(1.0F, destination.lengthSquared(), 0.00001F);

        assertThrows(IllegalArgumentException.class,
                () -> offsets.set(-1, new Quaternionf()));
        assertThrows(IllegalArgumentException.class,
                () -> offsets.set("", new Quaternionf()));
        assertThrows(IllegalArgumentException.class,
                () -> offsets.set(1, new Quaternionf(0.0F, 0.0F, 0.0F, 0.0F)));
    }

    @Test
    void renderOptionChangesRetainTheLiveOffsetContainer() {
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets();
        GltfRenderOptions options = GltfRenderOptions.DEFAULT.withNodeRotationOffsets(offsets);

        assertSame(offsets, options.nodeRotationOffsets());
        assertSame(offsets, options.withAnimation("Walk", 1.0F).nodeRotationOffsets());
        assertSame(offsets, options.withScene("Scene").nodeRotationOffsets());
    }

    private static void assertQuaternion(Quaternionf expected, Quaternionf actual) {
        assertEquals(expected.x, actual.x, 0.00001F);
        assertEquals(expected.y, actual.y, 0.00001F);
        assertEquals(expected.z, actual.z, 0.00001F);
        assertEquals(expected.w, actual.w, 0.00001F);
    }
}
