package me.danialisntcool.gltfapi.api.client;

import java.util.Objects;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record GltfLodLevel(float maximumDistance, GltfModelHandle model) {
    public GltfLodLevel {
        if (Float.isNaN(maximumDistance) || maximumDistance < 0.0F) {
            throw new IllegalArgumentException("LOD maximum distance must be non-negative");
        }
        Objects.requireNonNull(model);
    }
}
