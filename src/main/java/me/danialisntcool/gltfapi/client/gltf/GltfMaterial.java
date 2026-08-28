package me.danialisntcool.gltfapi.client.gltf;

public record GltfMaterial(
        GltfTextureInfo baseColorTexture,
        GltfTextureInfo metallicRoughnessTexture,
        GltfTextureInfo normalTexture,
        GltfTextureInfo occlusionTexture,
        GltfTextureInfo emissiveTexture,
        float red,
        float green,
        float blue,
        float alpha,
        float metallicFactor,
        float roughnessFactor,
        float normalScale,
        float occlusionStrength,
        float emissiveRed,
        float emissiveGreen,
        float emissiveBlue,
        AlphaMode alphaMode,
        float alphaCutoff,
        boolean doubleSided,
        boolean unlit
) {
    public enum AlphaMode {
        OPAQUE,
        MASK,
        BLEND
    }
}
