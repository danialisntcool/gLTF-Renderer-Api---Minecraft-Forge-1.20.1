package me.danialisntcool.gltfapi.api.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public enum GltfRenderMode {
    AUTO,
    BUFFERED,
    NATIVE;

    public boolean usesBuffered(boolean shaderPackOrShadowPass, boolean optimizedRenderer) {
        return shaderPackOrShadowPass || this == BUFFERED || this == AUTO && optimizedRenderer;
    }
}
