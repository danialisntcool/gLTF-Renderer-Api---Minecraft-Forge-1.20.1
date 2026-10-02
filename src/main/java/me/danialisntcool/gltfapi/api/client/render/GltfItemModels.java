package me.danialisntcool.gltfapi.api.client.render;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ModelEvent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class GltfItemModels {
    private static final Map<ResourceLocation, ResourceLocation> REPLACEMENTS = new ConcurrentHashMap<>();

    private GltfItemModels() {
    }

    public static void register(ResourceLocation item, ResourceLocation modelJson) {
        Objects.requireNonNull(item);
        Objects.requireNonNull(modelJson);
        ResourceLocation previous = REPLACEMENTS.putIfAbsent(item, modelJson);
        if (previous != null && !previous.equals(modelJson)) {
            throw new IllegalStateException("An item model replacement is already registered for " + item);
        }
    }

    public static void registerAdditional(ModelEvent.RegisterAdditional event) {
        REPLACEMENTS.values().forEach(event::register);
    }

    public static void apply(ModelEvent.ModifyBakingResult event) {
        REPLACEMENTS.forEach((item, replacement) -> {
            BakedModel model = event.getModels().get(replacement);
            if (model == null) {
                throw new IllegalStateException("Item model replacement was not baked: " + replacement);
            }
            event.getModels().put(new ModelResourceLocation(item, "inventory"), model);
        });
    }
}
