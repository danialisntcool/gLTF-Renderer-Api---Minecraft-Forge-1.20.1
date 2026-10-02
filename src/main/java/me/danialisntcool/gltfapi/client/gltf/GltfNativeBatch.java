package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;
import me.danialisntcool.gltfapi.generated.ModMetadata;

public final class GltfNativeBatch implements AutoCloseable {
    private static final java.util.ArrayDeque<FloatArrayList> POOL = new java.util.ArrayDeque<>();
    private static long retainedBytes;
    private final Map<Key, FloatArrayList> groups = new LinkedHashMap<>();
    private final Matrix4f pose = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private final float[] instance = new float[32];
    private int packedFloats;

    public void add(GltfModel model, Matrix4f modelView, int light, int overlay, GltfRenderOptions options) {
        GltfRenderState state = model.renderState(options.animation(), options.animationTimeSeconds(),
                options.scene(), options.nodeRotationOffsets());
        for (GltfPrimitive primitive : model.primitives()) {
            if (!state.visibleNodes().get(primitive.nodeIndex())) continue;
            FloatArrayList data = groups.computeIfAbsent(new Key(model, state, primitive), ignored -> acquire());
            for (Matrix4f local : primitive.instances()) {
                if ((long) (packedFloats + 32) * Float.BYTES > ModMetadata.STAGING_BYTES) {
                    flush();
                    data = groups.computeIfAbsent(new Key(model, state, primitive), ignored -> acquire());
                }
                pose.set(modelView).mul(state.transforms()[primitive.nodeIndex()]).mul(local);
                pack(pose, light, overlay, instance, normal);
                data.addElements(data.size(), instance);
                packedFloats += 32;
            }
        }
    }

    static void pack(Matrix4f pose, int light, int overlay, float[] output, Matrix3f normal) {
        pose.get(output, 0);
        normal.set(pose).invert().transpose();
        output[16] = normal.m00(); output[17] = normal.m01(); output[18] = normal.m02(); output[19] = 0;
        output[20] = normal.m10(); output[21] = normal.m11(); output[22] = normal.m12(); output[23] = 0;
        output[24] = normal.m20(); output[25] = normal.m21(); output[26] = normal.m22(); output[27] = 0;
        output[28] = light & 65535; output[29] = light >>> 16 & 65535;
        output[30] = overlay & 65535; output[31] = overlay >>> 16 & 65535;
    }

    public void flush() {
        if (groups.isEmpty()) return;
        RenderSystem.assertOnRenderThread();
        try (GltfNativeState ignored = new GltfNativeState()) {
            Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
            Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor();
            RenderSystem.setShaderTexture(6, RenderSystem.getShaderTexture(1));
            RenderSystem.setShader(GltfShaders::modelShader);
            var shader = GltfShaders.modelShader();
            Matrix4f identity = new Matrix4f();
            try {
                for (Map.Entry<Key, FloatArrayList> entry : groups.entrySet()) {
                    Key key = entry.getKey();
                    GltfRenderer.configureNativeShader(key.model(), key.state(), key.primitive(), 0, 0);
                    shader.getUniform("HasInstances").set(1);
                    int chunkSize = Math.min(GltfInstanceBuffer.maxInstances(),
                            Math.max(1, ModMetadata.STAGING_BYTES / 128)) * 32;
                    for (int first = 0; first < entry.getValue().size(); first += chunkSize) {
                        int size = Math.min(chunkSize, entry.getValue().size() - first);
                        int texture = GltfInstanceBuffer.upload(entry.getValue().elements(), first, size);
                        key.primitive().vertexBuffer().drawWithShader(identity, RenderSystem.getProjectionMatrix(),
                                shader, size / 32, texture);
                    }
                }
            } finally {
                shader.getUniform("HasInstances").set(0);
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        for (FloatArrayList data : groups.values()) {
            data.clear();
            long size = (long) data.elements().length * Float.BYTES;
            if (POOL.size() < 64 && size + retainedBytes <= ModMetadata.STAGING_BYTES) {
                POOL.addLast(data);
                retainedBytes += size;
            }
        }
        groups.clear();
        packedFloats = 0;
    }

    private static FloatArrayList acquire() {
        FloatArrayList data = POOL.pollFirst();
        if (data == null) return new FloatArrayList();
        retainedBytes -= (long) data.elements().length * Float.BYTES;
        return data;
    }

    static void clearPool() {
        POOL.clear();
        retainedBytes = 0;
    }

    private record Key(GltfModel model, GltfRenderState state, GltfPrimitive primitive) {
    }
}
