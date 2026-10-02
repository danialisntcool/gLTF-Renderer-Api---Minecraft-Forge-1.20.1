package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GltfTriangleSorterTest {
    private final int[] indices = {0, 1, 2, 3, 4, 5};
    private final float[] positions = {0, 0, 1, 1, 0, 1, 0, 1, 1, 0, 0, 5, 1, 0, 5, 0, 1, 5};

    @Test
    void sortsBackToFrontAndReusesStorageWhenCameraMoves() {
        GltfTriangleSorter sorter = new GltfTriangleSorter(indices);
        int[] first = sorter.sort(new Matrix4f(), positions);
        assertArrayEquals(new int[]{3, 4, 5, 0, 1, 2}, first);
        assertSame(first, sorter.sort(new Matrix4f(), positions));
        int[] moved = sorter.sort(new Matrix4f().translation(0, 0, -6), positions);
        assertSame(first, moved);
        assertArrayEquals(indices, moved);
    }

    @Test
    void rebuildsCentersForANewAnimatedPose() {
        GltfTriangleSorter sorter = new GltfTriangleSorter(indices);
        sorter.sort(new Matrix4f(), positions);
        float[] deformed = positions.clone();
        deformed[2] = deformed[5] = deformed[8] = 9;
        assertArrayEquals(indices, sorter.sort(new Matrix4f(), deformed));
    }

    @Test
    void handlesEmptyGeometryAndKeepsTiesStable() {
        assertEquals(0, new GltfTriangleSorter(new int[0]).sort(new Matrix4f(), new float[0]).length);
        assertArrayEquals(indices, new GltfTriangleSorter(indices).sort(new Matrix4f(), new float[18]));
    }
}
