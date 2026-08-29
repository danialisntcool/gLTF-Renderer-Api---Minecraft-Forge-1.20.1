package me.danialisntcool.gltfapi.api.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@OnlyIn(Dist.CLIENT)
public final class GltfNodeRotationOffsets {
    private static final GltfNodeRotationOffsets EMPTY = new GltfNodeRotationOffsets(false);

    private final Map<String, Quaternionf> named = new HashMap<>();
    private final Map<Integer, Quaternionf> indexed = new HashMap<>();
    private final boolean mutable;
    private long revision;

    public GltfNodeRotationOffsets() {
        this(true);
    }

    private GltfNodeRotationOffsets(boolean mutable) {
        this.mutable = mutable;
    }

    static GltfNodeRotationOffsets empty() {
        return EMPTY;
    }

    public GltfNodeRotationOffsets set(String nodeName, Quaternionfc rotation) {
        requireMutable();
        String name = Objects.requireNonNull(nodeName);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Node name must not be blank");
        }
        set(named, name, rotation);
        revision++;
        return this;
    }

    public GltfNodeRotationOffsets set(int nodeIndex, Quaternionfc rotation) {
        requireMutable();
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("Node index must be non-negative");
        }
        set(indexed, nodeIndex, rotation);
        revision++;
        return this;
    }

    public GltfNodeRotationOffsets remove(String nodeName) {
        requireMutable();
        if (named.remove(Objects.requireNonNull(nodeName)) != null) {
            revision++;
        }
        return this;
    }

    public GltfNodeRotationOffsets remove(int nodeIndex) {
        requireMutable();
        if (indexed.remove(nodeIndex) != null) {
            revision++;
        }
        return this;
    }

    public GltfNodeRotationOffsets clear() {
        requireMutable();
        if (!isEmpty()) {
            named.clear();
            indexed.clear();
            revision++;
        }
        return this;
    }

    public boolean isEmpty() {
        return named.isEmpty() && indexed.isEmpty();
    }

    public long revision() {
        return revision;
    }

    public boolean resolve(int nodeIndex, String nodeName, Quaternionf destination) {
        Objects.requireNonNull(destination);
        Quaternionf rotation = indexed.get(nodeIndex);
        if (rotation == null && nodeName != null) {
            rotation = named.get(nodeName);
        }
        if (rotation == null) {
            return false;
        }
        destination.set(rotation);
        return true;
    }

    private <K> void set(Map<K, Quaternionf> rotations, K key, Quaternionfc rotation) {
        Objects.requireNonNull(rotation);
        float lengthSquared = rotation.lengthSquared();
        if (!Float.isFinite(rotation.x()) || !Float.isFinite(rotation.y())
                || !Float.isFinite(rotation.z()) || !Float.isFinite(rotation.w())
                || !Float.isFinite(lengthSquared) || lengthSquared == 0.0F) {
            throw new IllegalArgumentException("Node rotation must be finite and non-zero");
        }
        rotations.computeIfAbsent(key, ignored -> new Quaternionf()).set(rotation).normalize();
    }

    private void requireMutable() {
        if (!mutable) {
            throw new UnsupportedOperationException("The default node rotation offsets are immutable");
        }
    }
}
