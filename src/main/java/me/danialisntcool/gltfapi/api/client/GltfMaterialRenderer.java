package me.danialisntcool.gltfapi.api.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@FunctionalInterface
public interface GltfMaterialRenderer {
    boolean render(GltfMaterialRenderContext context);
}
