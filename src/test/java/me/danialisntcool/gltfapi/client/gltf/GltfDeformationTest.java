package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GltfDeformationTest {
    private static GltfPrimitive primitive(float[] normals) {
        GltfMaterial material = new GltfMaterial(null, null, null, null, null,
                1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, GltfMaterial.AlphaMode.OPAQUE, 0.5F, false, false);
        return new GltfPrimitive(new float[]{1, 2, 3}, normals, new float[2], new float[2],
                new float[]{1, 1, 1, 1}, new float[]{0, 1, 0, 1}, new float[]{0.2F, 0.3F, 0.1F, 0.4F},
                new float[][]{{2, -1, 0.5F}}, new float[][]{{0.2F, 0.1F, -0.3F}},
                new int[]{0, 0, 0}, material, new Matrix4f(), 0, 0, List.of(new Matrix4f()));
    }

    @Test
    void flatSkinningMatchesMatrixReferenceAfterMorphing() {
        GltfPrimitive primitive = primitive(new float[]{0.4F, 0.5F, 0.6F});
        Matrix4f[] matrices = {new Matrix4f().translate(4, -2, 1).rotateY(0.3F).scale(2, 1, 3),
                new Matrix4f().translate(-2, 3, 4).rotateX(-0.4F)};
        float[] values = new float[32];
        matrices[0].get(values, 0);
        matrices[1].get(values, 16);
        GltfDeformedGeometry result = GltfRenderer.deformGeometry(primitive, values, new float[]{0.6F});
        Vector3f position = new Vector3f(1, 2, 3).fma(0.6F, new Vector3f(2, -1, 0.5F));
        Vector3f normal = new Vector3f(0.4F, 0.5F, 0.6F).fma(0.6F, new Vector3f(0.2F, 0.1F, -0.3F));
        Vector3f expectedPosition = new Vector3f();
        Vector3f expectedNormal = new Vector3f();
        for (int influence = 0; influence < 4; influence++) {
            Matrix4f matrix = matrices[(int) primitive.joints()[influence]];
            expectedPosition.fma(primitive.weights()[influence], matrix.transformPosition(new Vector3f(position)));
            expectedNormal.fma(primitive.weights()[influence], matrix.transformDirection(new Vector3f(normal)));
        }
        expectedNormal.normalize();
        assertArrayEquals(new float[]{expectedPosition.x, expectedPosition.y, expectedPosition.z}, result.positions(), 0.00001F);
        assertArrayEquals(new float[]{expectedNormal.x, expectedNormal.y, expectedNormal.z}, result.normals(), 0.00001F);
        assertArrayEquals(new float[]{1, 2, 3}, primitive.positions());
    }

    @Test
    void zeroNormalsRemainFinite() {
        GltfPrimitive primitive = primitive(new float[3]);
        float[] values = new float[32];
        new Matrix4f().get(values, 0);
        new Matrix4f().get(values, 16);
        GltfDeformedGeometry result = GltfRenderer.deformGeometry(primitive, values, new float[]{0});
        assertArrayEquals(new float[3], result.normals());
    }
}
