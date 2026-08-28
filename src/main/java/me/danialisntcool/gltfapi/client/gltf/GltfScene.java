package me.danialisntcool.gltfapi.client.gltf;

import java.util.Arrays;

record GltfScene(String name, int[] roots) {
    GltfScene {
        roots = Arrays.copyOf(roots, roots.length);
    }
}
