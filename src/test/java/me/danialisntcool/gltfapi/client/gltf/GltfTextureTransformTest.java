package me.danialisntcool.gltfapi.client.gltf;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GltfTextureTransformTest {
    @Test
    void scalesBeforeRotatingAndThenAddsOffset() {
        GltfTextureInfo texture = new GltfTextureInfo(new ResourceLocation("test", "texture"),
                0, 4, 5, 2, 3, (float) (Math.PI / 2), GltfSampler.DEFAULT);
        float[] source = {1, 1, 0, 0};
        assertArrayEquals(new float[]{1, 7, 4, 5}, texture.transformedCoordinates(source), 0.00001F);
        assertArrayEquals(new float[]{1, 1, 0, 0}, source);
    }

    @Test
    void preservesMirroringAndCoordinatesOutsideTheUnitSquare() {
        GltfTextureInfo texture = new GltfTextureInfo(new ResourceLocation("test", "texture"),
                0, 0, 0, -2, 3, 0, GltfSampler.DEFAULT);
        assertArrayEquals(new float[]{-4, -3}, texture.transformedCoordinates(new float[]{2, -1}));
    }
}
