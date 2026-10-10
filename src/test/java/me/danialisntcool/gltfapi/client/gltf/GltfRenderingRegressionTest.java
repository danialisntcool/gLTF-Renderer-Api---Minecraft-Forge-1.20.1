package me.danialisntcool.gltfapi.client.gltf;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import static org.junit.jupiter.api.Assertions.*;

final class GltfRenderingRegressionTest {
    @Test
    void guiUnprojectionPreservesScreenPositionWithNonIdentityModelView() {
        Matrix4f projection = new Matrix4f().ortho(0, 960, 540, 0, 1000, 3000);
        Matrix4f view = new Matrix4f().translate(0, 0, -2000);
        Matrix4f combined = new Matrix4f(projection).mul(view);
        Vector3f expected = new Vector3f(-0.5F, 0.25F, -0.15F);
        Vector3f position = GltfGuiIconCache.unproject(new Matrix4f(combined).invert(),
                expected.x, expected.y, expected.z);
        Vector3f actual = combined.transformProject(position);
        assertEquals(expected.x, actual.x, 0.00001F);
        assertEquals(expected.y, actual.y, 0.00001F);
        assertEquals(expected.z, actual.z, 0.00001F);
    }

    @Test
    void labPbrMetalUsesAlbedoAsReflectanceAndNeverReservedEmissionValue() {
        int pixel = GltfShaderMaterialTextures.encodeSpecular(0.25F, 1, 2, true);
        assertEquals(191, pixel & 255);
        assertEquals(255, pixel >>> 8 & 255);
        assertEquals(0, pixel >>> 16 & 255);
        assertEquals(254, pixel >>> 24 & 255);
    }

    @Test
    void labPbrDielectricRetainsFourPercentReflectanceAndNoEmission() {
        int pixel = GltfShaderMaterialTextures.encodeSpecular(1, 0, 0, true);
        assertEquals(0, pixel & 255);
        assertEquals(10, pixel >>> 8 & 255);
        assertEquals(0, pixel >>> 24 & 255);
    }

    @Test
    void oldPbrKeepsMetalAndEmissionInTheirOwnChannels() {
        int pixel = GltfShaderMaterialTextures.encodeSpecular(0, 0.5F, 0.25F, false);
        assertEquals(255, pixel & 255);
        assertEquals(128, pixel >>> 8 & 255);
        assertEquals(64, pixel >>> 16 & 255);
        assertEquals(255, pixel >>> 24 & 255);
    }

    @Test
    void materialUvWrapHandlesNegativeRepeatMirroringAndClamp() {
        assertEquals(0.75F, GltfShaderMaterialTextures.wrap(-0.25F, 10497));
        assertEquals(0.25F, GltfShaderMaterialTextures.wrap(-0.25F, GL14.GL_MIRRORED_REPEAT));
        assertEquals(0.75F, GltfShaderMaterialTextures.wrap(1.25F, GL14.GL_MIRRORED_REPEAT));
        assertEquals(0, GltfShaderMaterialTextures.wrap(-2, GL12.GL_CLAMP_TO_EDGE));
        assertEquals(1, GltfShaderMaterialTextures.wrap(2, GL12.GL_CLAMP_TO_EDGE));
    }

    @Test
    void persistentAllocationRoundsUpWithoutWrapping() {
        assertEquals(4096, GltfPersistentStream.capacity(1));
        assertEquals(4096, GltfPersistentStream.capacity(4096));
        assertEquals(8192, GltfPersistentStream.capacity(4097));
        assertEquals(524288, GltfPersistentStream.capacity(300000));
        assertThrows(IllegalArgumentException.class, () -> GltfPersistentStream.capacity(0));
        assertThrows(IllegalArgumentException.class, () -> GltfPersistentStream.capacity(Integer.MAX_VALUE));
    }

    @Test
    void persistentBudgetRejectsOverflowAndAllowsExactFit() {
        assertTrue(GltfPersistentStream.fitsBudget(8192, 4096, 12288));
        assertFalse(GltfPersistentStream.fitsBudget(8193, 4096, 12288));
        assertTrue(GltfPersistentStream.fitsBudget(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE));
        assertFalse(GltfPersistentStream.fitsBudget(Long.MAX_VALUE - 1, 2, Long.MAX_VALUE));
        assertFalse(GltfPersistentStream.fitsBudget(-1, 1, 4096));
        assertFalse(GltfPersistentStream.fitsBudget(0, 0, 4096));
    }

    @Test
    void clientConfigRemovesObsoleteExperimentalStreamingSwitch() {
        var config = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        config.set("experimental.persistentStreaming", true);
        me.danialisntcool.gltfapi.client.GltfClientConfig.SPEC.correct(config);
        assertFalse(config.contains("experimental"));
        assertEquals("LAB_PBR", java.util.Objects.toString(config.get("shaderMaterialFormat")));
    }
}
