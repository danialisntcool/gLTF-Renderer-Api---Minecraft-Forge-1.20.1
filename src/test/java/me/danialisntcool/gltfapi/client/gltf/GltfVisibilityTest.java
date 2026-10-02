package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.api.client.GltfBounds;
import me.danialisntcool.gltfapi.api.client.GltfNodeRotationOffsets;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class GltfVisibilityTest {
    private static GltfPrimitive primitive(boolean skin, boolean morph) {
        GltfMaterial material = new GltfMaterial(null, null, null, null, null,
                1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, GltfMaterial.AlphaMode.OPAQUE, 0.5F, false, false);
        return new GltfPrimitive(new float[]{1, 2, 3, -1, -2, -3}, new float[]{0, 1, 0, 0, 1, 0},
                new float[4], new float[4], new float[]{1, 1, 1, 1, 1, 1, 1, 1},
                skin ? new float[]{0, 1, 0, 0, 0, 1, 0, 0} : null,
                skin ? new float[]{0.3F, 0.7F, 0, 0, 0.8F, 0.2F, 0, 0} : null,
                morph ? new float[][]{{2, -1, 0.5F, -2, 3, -1}} : new float[0][],
                morph ? new float[][]{new float[6]} : new float[0][], new int[]{0, 1, 0},
                material, new Matrix4f(), 0, skin ? 0 : -1, List.of(new Matrix4f().translate(1, 0, 0)));
    }

    private static GltfNode node(String name, int parent, int[] children, Vector3f translation, float[] weights) {
        return new GltfNode(name, parent, children, -1, -1, null, translation,
                new Quaternionf(), new Vector3f(1), weights);
    }

    @Test
    void boundsFollowRotationsTranslationsAndNegativeMorphWeights() {
        GltfPrimitive primitive = primitive(false, true);
        GltfModel model = new GltfModel(new ResourceLocation("test", "bounds"), List.of(primitive), Map.of(),
                List.of(node("Root", -1, new int[0], new Vector3f(7, -2, 1), new float[]{-0.8F})),
                List.of(new GltfScene("Scene", new int[]{0})), 0, List.of(), List.of());
        GltfNodeRotationOffsets offsets = new GltfNodeRotationOffsets().set("Root", new Quaternionf().rotateY(1.4F));
        GltfRenderOptions options = GltfRenderOptions.DEFAULT.withNodeRotationOffsets(offsets);
        GltfBounds bounds = model.renderBounds(options);
        GltfRenderState state = model.renderState(null, 0, null, offsets);
        GltfDeformedGeometry deformed = GltfRenderer.deformGeometry(primitive, null, new float[]{-0.8F});
        Matrix4f transform = new Matrix4f(state.transforms()[0]).mul(primitive.instances().get(0));
        for (int index = 0; index < deformed.positions().length; index += 3) {
            Vector3f vertex = transform.transformPosition(new Vector3f(deformed.positions()[index],
                    deformed.positions()[index + 1], deformed.positions()[index + 2]));
            assertContained(bounds, vertex);
        }
        assertSame(bounds, model.renderBounds(options));
        offsets.set("Root", new Quaternionf().rotateX(-0.4F));
        assertNotSame(bounds, model.renderBounds(options));
    }

    @Test
    void conservativeSkinBoundsContainWeightedJointPositions() {
        GltfPrimitive primitive = primitive(true, true);
        GltfModel model = new GltfModel(new ResourceLocation("test", "skin_bounds"), List.of(primitive), Map.of(),
                List.of(node("Root", -1, new int[]{1, 2}, new Vector3f(4, 5, 6), new float[]{0.7F}),
                        node("JointA", 0, new int[0], new Vector3f(10, 0, 0), new float[0]),
                        node("JointB", 0, new int[0], new Vector3f(0, -7, 2), new float[0])),
                List.of(new GltfScene("Scene", new int[]{0})), 0, List.of(),
                List.of(new GltfSkin("Skin", new int[]{1, 2}, List.of(new Matrix4f(), new Matrix4f()))));
        GltfRenderOptions options = GltfRenderOptions.DEFAULT.withNodeRotationOffsets(
                new GltfNodeRotationOffsets().set("JointA", new Quaternionf().rotateZ(0.6F)));
        GltfRenderState state = model.renderState(null, 0, null, options.nodeRotationOffsets());
        GltfBounds bounds = model.renderBounds(options);
        GltfDeformedGeometry deformed = GltfRenderer.deformGeometry(primitive,
                model.skinMatrices(primitive, state), state.morphWeights()[0]);
        Matrix4f transform = new Matrix4f(state.transforms()[0]).mul(primitive.instances().get(0));
        for (int index = 0; index < deformed.positions().length; index += 3) {
            assertContained(bounds, transform.transformPosition(new Vector3f(deformed.positions()[index],
                    deformed.positions()[index + 1], deformed.positions()[index + 2])));
        }
        assertFalse(model.supportsIconCache(options));
    }

    @Test
    void reconstructsWorldBoundsFromCameraRelativePose() {
        Matrix3f cameraRotation = new Matrix3f().rotateY(0.7F).rotateX(-0.2F);
        Vec3 camera = new Vec3(80, 42, -22);
        Matrix4f expected = new Matrix4f().translate(91, 43, -17).rotateZ(0.3F).scale(2, 1, 3);
        Matrix4f pose = new Matrix4f().set3x3(cameraRotation)
                .translate((float) -camera.x, (float) -camera.y, (float) -camera.z).mul(expected);
        Matrix4f actual = GltfWorldQueue.worldTransform(pose, new Matrix3f(cameraRotation).invert(), camera);
        assertTrue(expected.equals(actual, 0.00002F));
    }

    @Test
    void iconProjectionPreservesSizeWhenMovingBetweenInventorySlots() {
        GltfBounds bounds = new GltfBounds(new Vector3f(-1), new Vector3f(1));
        Matrix4f projection = new Matrix4f().ortho(-10, 10, -10, 10, -10, 10);
        var first = GltfGuiIconCache.project(bounds, new Matrix4f(projection).translate(1, 0, 0));
        var second = GltfGuiIconCache.project(bounds, new Matrix4f(projection).translate(6, -3, 0));
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.right() - first.left(), second.right() - second.left(), 0.000001F);
        assertEquals(first.top() - first.bottom(), second.top() - second.bottom(), 0.000001F);
        assertNull(GltfGuiIconCache.project(bounds, new Matrix4f(projection).translate(0, 0, 50)));
        assertNull(GltfGuiIconCache.project(bounds, new Matrix4f().scale(0)));
    }

    private static void assertContained(GltfBounds bounds, Vector3f point) {
        Vector3f min = bounds.minimum();
        Vector3f max = bounds.maximum();
        assertTrue(point.x >= min.x && point.y >= min.y && point.z >= min.z);
        assertTrue(point.x <= max.x && point.y <= max.y && point.z <= max.z);
    }
}
