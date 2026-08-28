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
}
