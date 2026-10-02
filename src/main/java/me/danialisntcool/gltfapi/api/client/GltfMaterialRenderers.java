package me.danialisntcool.gltfapi.api.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class GltfMaterialRenderers {
    private static final Map<ResourceLocation, GltfMaterialRenderer> RENDERERS = new ConcurrentHashMap<>();

    private GltfMaterialRenderers() {
    }

    public static void register(GltfModelHandle model, GltfMaterialRenderer renderer) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(renderer);
        if (RENDERERS.putIfAbsent(model.location(), renderer) != null) {
            throw new IllegalStateException("A material renderer is already registered for " + model.location());
        }
    }

    public static boolean unregister(GltfModelHandle model, GltfMaterialRenderer renderer) {
        return RENDERERS.remove(model.location(), renderer);
    }

    public static GltfMaterialRenderer find(ResourceLocation model) {
        return RENDERERS.get(model);
    }
}
