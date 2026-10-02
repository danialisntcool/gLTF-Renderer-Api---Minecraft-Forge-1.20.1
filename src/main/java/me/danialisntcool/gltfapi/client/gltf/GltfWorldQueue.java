package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderRequest;
import me.danialisntcool.gltfapi.api.client.GltfMaterialRenderers;
import me.danialisntcool.gltfapi.client.GltfClientConfig;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public final class GltfWorldQueue {
    private static final java.util.Map<GltfShaderPackCompat.EntityState, List<GltfRenderRequest>> REQUESTS = new java.util.LinkedHashMap<>();
    private static final java.util.ArrayDeque<List<GltfRenderRequest>> FREE = new java.util.ArrayDeque<>();
    private static int requestCount;
    private static Frustum frustum;
    private static Vec3 camera;
    private static boolean collecting;
    private static boolean flushing;
    private static int drawTarget;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            reset();
            frustum = event.getFrustum();
            camera = event.getCamera().getPosition();
            drawTarget = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            collecting = !GltfRenderer.isRenderingShadowPass();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void flushStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) flush();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) reset();
    }

    public static boolean submit(GltfModelHandle handle, GltfRenderContext context) {
        GltfModel model = GltfModelManager.getInstance().getModel(handle.location()).orElse(null);
        if (model == null) return false;
        GltfShaderPackCompat.EntityState shaderState = GltfShaderPackCompat.captureEntityState();
        if (!collecting || frustum == null || camera == null || flushing || !GltfClientConfig.WORLD_BATCHING.get()
                || shaderState == null
                || GltfRenderer.isRenderingShadowPass() || model.hasBlendedPrimitives()
                || GltfRenderer.isOrthographic(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix())
                || drawTarget != org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING)
                || GltfMaterialRenderers.find(handle.location()) != null
                || context.buffers() != Minecraft.getInstance().renderBuffers().bufferSource()) {
            return GltfApi.render(handle, context);
        }
        Matrix4f world = worldTransform(context.poseStack().last().pose(),
                com.mojang.blaze3d.systems.RenderSystem.getInverseViewRotationMatrix(), camera);
        GltfRenderRequest request = new GltfRenderRequest(handle, context);
        double distance = GltfClientConfig.MODEL_RENDER_DISTANCE.get();
        var box = model.renderBounds(request.context().options()).transformed(new Matrix4f(world)
                .mul(context.options().transformationMatrix()));
        double nearestX = Math.max(box.minX, Math.min(camera.x, box.maxX));
        double nearestY = Math.max(box.minY, Math.min(camera.y, box.maxY));
        double nearestZ = Math.max(box.minZ, Math.min(camera.z, box.maxZ));
        if (!frustum.isVisible(box) || distance > 0 && camera.distanceToSqr(nearestX, nearestY, nearestZ) > distance * distance) {
            GltfRenderMetrics.culled();
            return false;
        }
        if (requestCount >= Math.max(1, ModMetadata.WORLD_QUEUE_LIMIT)) flush();
        REQUESTS.computeIfAbsent(shaderState, ignored -> {
            List<GltfRenderRequest> list = FREE.pollFirst();
            return list == null ? new ArrayList<>() : list;
        }).add(request);
        requestCount++;
        return true;
    }

    public static void flush() {
        if (REQUESTS.isEmpty() || flushing) return;
        flushing = true;
        try {
            int previousTarget = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            var previousState = GltfShaderPackCompat.captureEntityState();
            try {
                com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
                for (var entry : REQUESTS.entrySet()) {
                    GltfShaderPackCompat.applyEntityState(entry.getKey());
                    GltfApi.renderBatch(entry.getValue());
                }
            } finally {
                if (previousState != null) GltfShaderPackCompat.applyEntityState(previousState);
                com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, previousTarget);
            }
        } finally {
            releaseRequests();
            flushing = false;
        }
    }

    public static void reset() {
        releaseRequests();
        collecting = false;
        frustum = null;
        camera = null;
    }

    private static void releaseRequests() {
        for (List<GltfRenderRequest> requests : REQUESTS.values()) {
            int size = requests.size();
            requests.clear();
            if (size <= 4096 && FREE.size() < 16) FREE.addLast(requests);
        }
        REQUESTS.clear();
        requestCount = 0;
    }

    static Matrix4f worldTransform(org.joml.Matrix4fc pose, org.joml.Matrix3fc inverseView, Vec3 camera) {
        return new Matrix4f().translation((float) camera.x, (float) camera.y, (float) camera.z)
                .mul(new Matrix4f().set3x3(inverseView)).mul(pose);
    }
}
