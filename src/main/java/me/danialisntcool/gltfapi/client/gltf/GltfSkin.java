package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix4f;

import java.util.Arrays;
import java.util.List;

record GltfSkin(String name, int[] joints, List<Matrix4f> inverseBindMatrices) {
    GltfSkin {
        joints = Arrays.copyOf(joints, joints.length);
        inverseBindMatrices = inverseBindMatrices.stream().map(Matrix4f::new).toList();
    }
}
