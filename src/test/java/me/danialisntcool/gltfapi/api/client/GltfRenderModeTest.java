package me.danialisntcool.gltfapi.api.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GltfRenderModeTest {
    @Test
    void automaticModeSelectsTheStandardStreamForOptimizedRenderers() {
        assertTrue(GltfRenderMode.AUTO.usesBuffered(false, true));
        assertFalse(GltfRenderMode.AUTO.usesBuffered(false, false));
    }

    @Test
    void explicitModesAreHonoredWithoutAShaderPack() {
        assertTrue(GltfRenderMode.BUFFERED.usesBuffered(false, false));
        assertFalse(GltfRenderMode.NATIVE.usesBuffered(false, true));
    }

    @Test
    void noModeBypassesAnActiveShaderOrShadowPass() {
        for (GltfRenderMode mode : GltfRenderMode.values()) {
            assertTrue(mode.usesBuffered(true, false));
            assertTrue(mode.usesBuffered(true, true));
        }
    }
}
