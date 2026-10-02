package me.danialisntcool.gltfapi.client.benchmark;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfModelStatistics;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import me.danialisntcool.gltfapi.api.client.GltfRenderRequest;
import me.danialisntcool.gltfapi.api.client.GltfRenderMode;
import me.danialisntcool.gltfapi.client.gltf.GltfRenderMetrics;
import me.danialisntcool.gltfapi.client.gltf.GltfRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public final class GltfBenchmark {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long WARMUP_NANOS = 3_000_000_000L;
    private Session session;

    @SubscribeEvent
    public void registerCommands(RegisterClientCommandsEvent event) {
        var duration = Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                .executes(context -> start(ResourceLocationArgument.getId(context, "model"),
                        IntegerArgumentType.getInteger(context, "instances"),
                        IntegerArgumentType.getInteger(context, "seconds")))
                .then(Commands.literal("native").executes(context -> start(
                        ResourceLocationArgument.getId(context, "model"),
                        IntegerArgumentType.getInteger(context, "instances"),
                        IntegerArgumentType.getInteger(context, "seconds"), GltfRenderMode.NATIVE)))
                .then(Commands.literal("buffered").executes(context -> start(
                        ResourceLocationArgument.getId(context, "model"),
                        IntegerArgumentType.getInteger(context, "instances"),
                        IntegerArgumentType.getInteger(context, "seconds"), GltfRenderMode.BUFFERED)));
        event.getDispatcher().register(Commands.literal("gltfbenchmark")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start")
                        .then(Commands.argument("model", ResourceLocationArgument.id())
                                .executes(context -> start(ResourceLocationArgument.getId(context, "model"), 64, 10))
                                .then(Commands.argument("instances", IntegerArgumentType.integer(1, 4096))
                                        .executes(context -> start(ResourceLocationArgument.getId(context, "model"),
                                                IntegerArgumentType.getInteger(context, "instances"), 10))
                                        .then(duration))))
                .then(Commands.literal("stop").executes(context -> stop()))
                .then(Commands.literal("status").executes(context -> status())));
    }

    @SubscribeEvent
    public void render(RenderLevelStageEvent event) {
        Session active = session;
        if (active == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        long frameStart = System.nanoTime();
        if (active.firstFrameNanos == 0L) {
            active.firstFrameNanos = frameStart;
            active.lastFrameNanos = frameStart;
        }
        long elapsed = frameStart - active.firstFrameNanos;
        if (elapsed >= WARMUP_NANOS) {
            active.addFrame(frameStart - active.lastFrameNanos);
        }
        active.lastFrameNanos = frameStart;
        GltfRenderMetrics.Snapshot before = GltfRenderMetrics.snapshot();
        renderGrid(active, event);
        if (elapsed >= WARMUP_NANOS) {
            active.renderNanos += System.nanoTime() - frameStart;
            active.addMetrics(GltfRenderMetrics.snapshot().minus(before));
        }
        if (elapsed >= WARMUP_NANOS + active.durationNanos) {
            finish(active);
        }
    }

    private int start(ResourceLocation location, int instances, int seconds) {
        return start(location, instances, seconds, GltfRenderMode.AUTO);
    }

    private int start(ResourceLocation location, int instances, int seconds, GltfRenderMode mode) {
        GltfModelHandle model = GltfApi.model(location);
        if (!GltfApi.isLoaded(model)) {
            message("Model is not loaded: " + location);
            return 0;
        }
        GltfModelStatistics statistics = GltfApi.statistics(model).orElseThrow();
        float width = statistics.maximum().x - statistics.minimum().x;
        float height = statistics.maximum().y - statistics.minimum().y;
        float depth = statistics.maximum().z - statistics.minimum().z;
        float largest = Math.max(width, Math.max(height, depth));
        float scale = largest > 0.0F ? 1.25F / largest : 1.0F;
        session = new Session(model, location, instances, seconds * 1_000_000_000L, scale,
                statistics.triangles(), new double[Math.max(1024, seconds * 240)], mode);
        message("glTF benchmark warming up for 3 seconds: " + instances + " instances of " + location
                + " | requested " + mode + " | shaders " + GltfRenderer.isShaderPackInUse());
        return 1;
    }

    private int stop() {
        if (session == null) {
            message("No glTF benchmark is running");
            return 0;
        }
        Session active = session;
        session = null;
        report(active, true);
        return 1;
    }

    private int status() {
        Session active = session;
        if (active == null) {
            message("No glTF benchmark is running");
            return 0;
        }
        double elapsed = active.firstFrameNanos == 0L ? 0.0
                : (System.nanoTime() - active.firstFrameNanos) / 1_000_000_000.0;
        message(String.format(java.util.Locale.ROOT,
                "glTF benchmark: %.1fs elapsed, %d instances, %,d triangles/frame",
                elapsed, active.instances, (long) active.instances * active.triangles));
        return 1;
    }

    private void renderGrid(Session active, RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        int columns = (int) Math.ceil(Math.sqrt(active.instances * 16.0 / 9.0));
        int rows = (active.instances + columns - 1) / columns;
        float spacing = 1.65F;
        float distance = Math.max(8.0F, Math.max(columns, rows) * 1.15F);
        PoseStack poseStack = event.getPoseStack();
        List<GltfRenderRequest> requests = active.requests;
        requests.clear();
        for (int index = 0; index < active.instances; index++) {
            int column = index % columns;
            int row = index / columns;
            float horizontal = (column - (columns - 1) * 0.5F) * spacing;
            float vertical = ((rows - 1) * 0.5F - row) * spacing;
            double worldX = cameraPosition.x + look.x * distance + left.x * horizontal + up.x * vertical;
            double worldY = cameraPosition.y + look.y * distance + left.y * horizontal + up.y * vertical;
            double worldZ = cameraPosition.z + look.z * distance + left.z * horizontal + up.z * vertical;
            poseStack.pushPose();
            poseStack.translate(worldX - cameraPosition.x, worldY - cameraPosition.y, worldZ - cameraPosition.z);
            requests.add(new GltfRenderRequest(active.model, new GltfRenderContext(poseStack, buffers,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, active.options).withRenderMode(active.mode)));
            poseStack.popPose();
        }
        GltfApi.renderBatch(requests);
        buffers.endBatch();
    }

    private void finish(Session active) {
        session = null;
        report(active, false);
    }

    private void report(Session active, boolean stopped) {
        if (active.sampleCount == 0) {
            message(stopped ? "glTF benchmark stopped during warmup" : "glTF benchmark produced no samples");
            return;
        }
        double[] samples = Arrays.copyOf(active.frameMilliseconds, active.sampleCount);
        Arrays.sort(samples);
        double total = 0.0;
        for (double sample : samples) {
            total += sample;
        }
        double average = total / samples.length;
        double fps = 1000.0 / average;
        double median = percentile(samples, 0.50);
        double p95 = percentile(samples, 0.95);
        double p99 = percentile(samples, 0.99);
        double renderMilliseconds = active.renderNanos / 1_000_000.0 / Math.max(1, active.totalRenderedFrames);
        message(String.format(java.util.Locale.ROOT,
                "glTF benchmark complete | %s | %d instances | %,d triangles/frame | %.1f FPS | avg %.2f ms | median %.2f ms | p95 %.2f ms | p99 %.2f ms | glTF submit %.2f ms/frame",
                active.location, active.instances, (long) active.instances * active.triangles,
                fps, average, median, p95, p99, renderMilliseconds));
        double frames = Math.max(1, active.totalRenderedFrames);
        message(String.format(java.util.Locale.ROOT,
                "glTF benchmark counters | requested %s | shaders %s | indexed draws %.1f/frame | instanced draws %.1f/frame | indexed/instance uploads %.3f MiB/frame | CPU transformed %,d vertices/frame | bulk/indexed vertices %,d/frame | pose cache hits %.1f/frame | GPU buffer reuses %.1f/frame",
                active.mode, GltfRenderer.isShaderPackInUse(), active.draws / frames,
                active.instancedDraws / frames, active.uploadedBytes / frames / 1_048_576.0,
                Math.round(active.transformedVertices / frames), Math.round(active.streamedVertices / frames),
                active.poseCacheHits / frames, active.bufferReuses / frames));
    }

    private double percentile(double[] sorted, double percentile) {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }

    private void message(String text) {
        LOGGER.info("[glTF benchmark] {}", text);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(text));
        }
    }

    private static final class Session {
        private final GltfModelHandle model;
        private final ResourceLocation location;
        private final int instances;
        private final long durationNanos;
        private final float scale;
        private final int triangles;
        private final GltfRenderMode mode;
        private final GltfRenderOptions options;
        private final List<GltfRenderRequest> requests;
        private double[] frameMilliseconds;
        private int sampleCount;
        private int totalRenderedFrames;
        private long firstFrameNanos;
        private long lastFrameNanos;
        private long renderNanos;
        private long draws;
        private long instancedDraws;
        private long uploadedBytes;
        private long transformedVertices;
        private long streamedVertices;
        private long poseCacheHits;
        private long bufferReuses;

        private Session(GltfModelHandle model, ResourceLocation location, int instances,
                        long durationNanos, float scale, int triangles, double[] frameMilliseconds, GltfRenderMode mode) {
            this.model = model;
            this.location = location;
            this.instances = instances;
            this.durationNanos = durationNanos;
            this.scale = scale;
            this.triangles = triangles;
            this.frameMilliseconds = frameMilliseconds;
            this.mode = mode;
            this.options = GltfRenderOptions.DEFAULT.withTransform(
                    new Vector3f(), new org.joml.Quaternionf(), new Vector3f(scale));
            this.requests = new ArrayList<>(instances);
        }

        private void addMetrics(GltfRenderMetrics.Snapshot metrics) {
            draws += metrics.draws();
            instancedDraws += metrics.instancedDraws();
            uploadedBytes += metrics.uploadedBytes();
            transformedVertices += metrics.transformedVertices();
            streamedVertices += metrics.streamedVertices();
            poseCacheHits += metrics.poseCacheHits();
            bufferReuses += metrics.bufferReuses();
        }

        private void addFrame(long frameNanos) {
            if (sampleCount == frameMilliseconds.length) {
                frameMilliseconds = Arrays.copyOf(frameMilliseconds, frameMilliseconds.length * 2);
            }
            frameMilliseconds[sampleCount++] = frameNanos / 1_000_000.0;
            totalRenderedFrames++;
        }
    }
}
