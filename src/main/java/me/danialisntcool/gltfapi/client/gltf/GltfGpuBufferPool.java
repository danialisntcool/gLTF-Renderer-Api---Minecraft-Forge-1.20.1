package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.vertex.VertexFormat;
import me.danialisntcool.gltfapi.generated.ModMetadata;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

final class GltfGpuBufferPool {
    private static final Map<VertexFormat, ArrayDeque<GltfGpuBuffer>> FREE = new HashMap<>();
    private static long bytes;
    private static int count;

    private GltfGpuBufferPool() {
    }

    static GltfGpuBuffer acquire(VertexFormat format) {
        ArrayDeque<GltfGpuBuffer> buffers = FREE.get(format);
        GltfGpuBuffer buffer = buffers == null ? null : buffers.pollFirst();
        if (buffer == null) return new GltfGpuBuffer(format);
        bytes -= buffer.storageBytes();
        count--;
        return buffer;
    }

    static void release(VertexFormat format, GltfGpuBuffer buffer) {
        if (buffer.isInvalid() || count >= 64 || bytes + buffer.storageBytes() > ModMetadata.GPU_STREAM_CACHE_BYTES) {
            buffer.close();
            return;
        }
        FREE.computeIfAbsent(format, ignored -> new ArrayDeque<>()).addFirst(buffer);
        bytes += buffer.storageBytes();
        count++;
    }

    static void clear() {
        for (ArrayDeque<GltfGpuBuffer> buffers : FREE.values()) {
            for (GltfGpuBuffer buffer : buffers) buffer.close();
        }
        FREE.clear();
        bytes = 0L;
        count = 0;
    }
}
