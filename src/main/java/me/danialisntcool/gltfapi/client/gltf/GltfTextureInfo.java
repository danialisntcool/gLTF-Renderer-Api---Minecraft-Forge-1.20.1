package me.danialisntcool.gltfapi.client.gltf;

import net.minecraft.resources.ResourceLocation;

public record GltfTextureInfo(
        ResourceLocation texture,
        int textureCoordinate,
        float offsetX,
        float offsetY,
        float scaleX,
        float scaleY,
        float rotation,
        GltfSampler sampler
) {
    float[] transformedCoordinates(float[] source) {
        float[] transformed = new float[source.length];
        float sine = (float) Math.sin(rotation);
        float cosine = (float) Math.cos(rotation);
        for (int offset = 0; offset < source.length; offset += 2) {
            float u = source[offset] * scaleX;
            float v = source[offset + 1] * scaleY;
            transformed[offset] = offsetX + cosine * u - sine * v;
            transformed[offset + 1] = offsetY + sine * u + cosine * v;
        }
        return transformed;
    }
}
