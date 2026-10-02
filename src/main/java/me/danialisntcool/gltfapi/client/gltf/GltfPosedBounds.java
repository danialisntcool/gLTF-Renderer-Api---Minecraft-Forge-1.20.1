package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.api.client.GltfBounds;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class GltfPosedBounds {
    static GltfBounds calculate(GltfModel model, GltfRenderState state) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        Matrix4f transform = new Matrix4f();
        Matrix4f joint = new Matrix4f();
        for (GltfPrimitive primitive : model.primitives()) {
            if (!state.visibleNodes().get(primitive.nodeIndex())) continue;
            GltfBounds local = primitive.morphedBounds(state.morphWeights()[primitive.nodeIndex()]);
            float[] matrices = primitive.skinIndex() < 0 ? null : model.skinMatrices(primitive, state);
            for (Matrix4f instance : primitive.instances()) {
                transform.set(state.transforms()[primitive.nodeIndex()]).mul(instance);
                if (matrices == null || primitive.activeJoints().isEmpty()) {
                    include(local.transformed(transform), min, max);
                } else {
                    for (int index = primitive.activeJoints().nextSetBit(0); index >= 0;
                         index = primitive.activeJoints().nextSetBit(index + 1)) {
                        joint.set(matrices, index * 16);
                        include(local.transformed(new Matrix4f(transform).mul(joint)), min, max);
                    }
                }
            }
        }
        if (!Float.isFinite(min.x)) {
            min.zero();
            max.zero();
        }
        return new GltfBounds(min, max).inflated(0.001F);
    }

    private static void include(AABB box, Vector3f min, Vector3f max) {
        min.min(new Vector3f((float) box.minX, (float) box.minY, (float) box.minZ));
        max.max(new Vector3f((float) box.maxX, (float) box.maxY, (float) box.maxZ));
    }
}
