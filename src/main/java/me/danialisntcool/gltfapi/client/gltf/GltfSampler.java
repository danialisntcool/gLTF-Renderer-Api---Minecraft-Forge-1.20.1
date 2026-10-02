package me.danialisntcool.gltfapi.client.gltf;

public record GltfSampler(int magFilter, int minFilter, int wrapS, int wrapT) {
    public static final GltfSampler DEFAULT = new GltfSampler(9729, 9987, 10497, 10497);
}
