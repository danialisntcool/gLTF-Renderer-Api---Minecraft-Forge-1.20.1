package me.danialisntcool.gltfapi.api.client;

import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record GltfBounds(Vector3f minimum, Vector3f maximum) {
    public GltfBounds {
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

    public GltfBounds inflated(float amount) {
        if (amount < 0.0F) {
            throw new IllegalArgumentException("Bounds inflation cannot be negative");
        }
        return new GltfBounds(new Vector3f(minimum).sub(amount, amount, amount),
                new Vector3f(maximum).add(amount, amount, amount));
    }

    public AABB transformed(Matrix4fc transform) {
        Vector3f transformedMinimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f transformedMaximum = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f corner = new Vector3f();
        for (int bits = 0; bits < 8; bits++) {
            corner.set((bits & 1) == 0 ? minimum.x : maximum.x,
                    (bits & 2) == 0 ? minimum.y : maximum.y,
                    (bits & 4) == 0 ? minimum.z : maximum.z);
            transform.transformPosition(corner);
            transformedMinimum.min(corner);
            transformedMaximum.max(corner);
        }
        return new AABB(transformedMinimum.x, transformedMinimum.y, transformedMinimum.z,
                transformedMaximum.x, transformedMaximum.y, transformedMaximum.z);
    }
}
