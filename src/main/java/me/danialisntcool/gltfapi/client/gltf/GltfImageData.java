package me.danialisntcool.gltfapi.client.gltf;

record GltfImageData(byte[] bytes, boolean ktx2) {
    GltfImageData {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
