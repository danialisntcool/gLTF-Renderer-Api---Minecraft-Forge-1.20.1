package me.danialisntcool.gltfapi.api.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class GltfModelHandle {
    private final ResourceLocation location;

    GltfModelHandle(ResourceLocation location) {
        this.location = Objects.requireNonNull(location);
    }

    public ResourceLocation location() {
        return location;
    }

    public boolean isLoaded() {
        return GltfApi.isLoaded(this);
    }

    public Optional<GltfModelStatistics> statistics() {
        return GltfApi.statistics(this);
    }

    public Optional<String> loadFailure() {
        return GltfApi.loadFailure(this);
    }

    public List<String> animationNames() {
        return GltfApi.animationNames(this);
    }

    public List<String> sceneNames() {
        return GltfApi.sceneNames(this);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GltfModelHandle handle && location.equals(handle.location);
    }

    @Override
    public int hashCode() {
        return location.hashCode();
    }

    @Override
    public String toString() {
        return "GltfModelHandle[" + location + "]";
    }
}
