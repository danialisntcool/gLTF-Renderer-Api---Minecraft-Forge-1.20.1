package me.danialisntcool.gltfapi.api.client;

import org.joml.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record GltfModelStatistics(
        int primitives,
        int vertices,
        int triangles,
        long gpuBytes,
        Vector3f minimum,
        Vector3f maximum
) {
    public GltfModelStatistics {
        minimum = new Vector3f(minimum);
        maximum = new Vector3f(maximum);
    }

    @Override
    public Vector3f minimum() {
        return new Vector3f(minimum);
    }

    @Override
    public Vector3f maximum() {
        return new Vector3f(maximum);
    }
}
