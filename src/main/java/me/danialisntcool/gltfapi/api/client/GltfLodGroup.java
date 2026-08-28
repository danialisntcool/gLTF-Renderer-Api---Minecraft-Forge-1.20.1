package me.danialisntcool.gltfapi.api.client;

import java.util.List;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class GltfLodGroup {
    private final List<GltfLodLevel> levels;

    public GltfLodGroup(List<GltfLodLevel> levels) {
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("LOD group requires at least one level");
        }
        this.levels = List.copyOf(levels);
        float previous = -1.0F;
        for (GltfLodLevel level : this.levels) {
            if (level.maximumDistance() <= previous) {
                throw new IllegalArgumentException("LOD maximum distances must be strictly increasing");
            }
            previous = level.maximumDistance();
        }
    }

    public List<GltfLodLevel> levels() {
        return levels;
    }

    public GltfModelHandle select(float distance) {
        if (Float.isNaN(distance) || distance < 0.0F) {
            throw new IllegalArgumentException("LOD distance must be non-negative");
        }
        for (GltfLodLevel level : levels) {
            if (distance <= level.maximumDistance()) {
                return level.model();
            }
        }
        return levels.get(levels.size() - 1).model();
    }
}
