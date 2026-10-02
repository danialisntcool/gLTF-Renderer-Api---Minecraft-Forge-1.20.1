package me.danialisntcool.gltfapi.client.gltf;

import it.unimi.dsi.fastutil.ints.IntArrays;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class GltfTriangleSorter {
    private final int[] source;
    private final int[] triangles;
    private final int[] sorted;
    private final float[] distances;
    private final float[] centers;
    private final Vector3f center = new Vector3f();
    private final Matrix4f lastPose = new Matrix4f();
    private float[] lastPositions;
    private boolean valid;

    GltfTriangleSorter(int[] source) {
        this.source = source;
        triangles = new int[source.length / 3];
        sorted = new int[source.length];
        distances = new float[triangles.length];
        centers = new float[source.length];
    }

    int[] sort(Matrix4f pose, float[] positions) {
        boolean changed = positions != lastPositions;
        if (valid && !changed && lastPose.equals(pose)) {
            return sorted;
        }
        if (changed) {
            for (int triangle = 0; triangle < triangles.length; triangle++) {
                int offset = triangle * 3;
                center.zero();
                for (int corner = 0; corner < 3; corner++) {
                    int vertex = source[offset + corner] * 3;
                    center.add(positions[vertex], positions[vertex + 1], positions[vertex + 2]);
                }
                center.mul(1.0F / 3.0F);
                centers[offset] = center.x;
                centers[offset + 1] = center.y;
                centers[offset + 2] = center.z;
            }
        }
        for (int triangle = 0; triangle < triangles.length; triangle++) {
            int offset = triangle * 3;
            pose.transformPosition(centers[offset], centers[offset + 1], centers[offset + 2], center);
            distances[triangle] = center.lengthSquared();
            triangles[triangle] = triangle;
        }
        IntArrays.quickSort(triangles, (left, right) -> {
            int distance = Float.compare(distances[right], distances[left]);
            return distance != 0 ? distance : Integer.compare(left, right);
        });
        for (int triangle = 0; triangle < triangles.length; triangle++) {
            System.arraycopy(source, triangles[triangle] * 3, sorted, triangle * 3, 3);
        }
        lastPositions = positions;
        lastPose.set(pose);
        valid = true;
        return sorted;
    }
}
