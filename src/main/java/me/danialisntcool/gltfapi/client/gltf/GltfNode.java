package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.Arrays;

record GltfNode(
        String name,
        int parent,
        int[] children,
        int mesh,
        int skin,
        Matrix4f matrix,
        Vector3f translation,
        Quaternionf rotation,
        Vector3f scale,
        float[] weights
) {
    GltfNode {
        children = Arrays.copyOf(children, children.length);
        matrix = matrix == null ? null : new Matrix4f(matrix);
        translation = new Vector3f(translation);
        rotation = new Quaternionf(rotation);
        scale = new Vector3f(scale);
        weights = Arrays.copyOf(weights, weights.length);
    }

    Matrix4f localTransform(NodePose pose, Quaternionfc rotationOffset) {
        if (matrix != null) {
            Matrix4f result = new Matrix4f(matrix);
            return rotationOffset == null ? result : result.rotate(rotationOffset);
        }
        Matrix4f result = new Matrix4f().translation(pose.translation()).rotate(pose.rotation());
        if (rotationOffset != null) {
            result.rotate(rotationOffset);
        }
        return result.scale(pose.scale());
    }

    NodePose pose() {
        return new NodePose(new Vector3f(translation), new Quaternionf(rotation), new Vector3f(scale),
                Arrays.copyOf(weights, weights.length));
    }

    record NodePose(Vector3f translation, Quaternionf rotation, Vector3f scale, float[] weights) {
    }
}
