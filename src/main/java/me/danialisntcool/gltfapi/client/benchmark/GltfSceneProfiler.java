package me.danialisntcool.gltfapi.client.benchmark;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.danialisntcool.gltfapi.client.gltf.GltfGpuTiming;
import me.danialisntcool.gltfapi.client.gltf.GltfGuiIconCache;
import me.danialisntcool.gltfapi.client.gltf.GltfRenderMetrics;
import me.danialisntcool.gltfapi.client.gltf.GltfRenderer;
import me.danialisntcool.gltfapi.client.gltf.GltfWorldQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.Arrays;
import java.util.Locale;

public final class GltfSceneProfiler {
    private Session session;

    @SubscribeEvent
    public void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gltfprofile")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(context -> start(20))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                                .executes(context -> start(IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("stop").executes(context -> finish()))
                .then(Commands.literal("status").executes(context -> {
                    message(session == null ? "glTF real-scene profiler idle" : "glTF real-scene profiler running");
                    return 1;
                })));
    }

    private int start(int seconds) {
        GltfGpuTiming.clear();
        session = new Session(seconds);
        message("glTF real-scene profile started: 3-second warmup, then " + seconds
                + " seconds. Move normally or open the inventory; no synthetic models are added.");
        return 1;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void frame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        GltfGuiIconCache.beginFrame();
        GltfWorldQueue.reset();
        GltfGpuTiming.poll();
        Session active = session;
        if (active == null) return;
        if (Minecraft.getInstance().level == null) {
            finish();
            return;
        }
        long now = System.nanoTime();
        if (active.measuredAt == 0 && now - active.started >= 3_000_000_000L) {
            active.measuredAt = now;
            active.lastFrame = now;
            active.before = GltfRenderMetrics.snapshot();
            active.bufferedBefore = GltfRenderMetrics.bufferedSnapshot();
            active.shadersOn = GltfRenderer.isShaderPackInUse();
            active.shadersOff = !active.shadersOn;
            GltfGpuTiming.start();
        } else if (active.measuredAt != 0) {
            boolean shaders = GltfRenderer.isShaderPackInUse();
            active.shadersOn |= shaders;
            active.shadersOff |= !shaders;
            active.frameNanos += now - active.lastFrame;
            active.frames++;
            if (active.frameTimes.size() < 100_000) active.frameTimes.add(now - active.lastFrame);
            active.lastFrame = now;
            if (now - active.measuredAt >= active.seconds * 1_000_000_000L) finish();
        }
    }

    private int finish() {
        Session active = session;
        session = null;
        GltfGpuTiming.stop();
        if (active == null || active.frames == 0) {
            message("glTF real-scene profile stopped without enough measured frames.");
            GltfGpuTiming.clear();
            return 0;
        }
        GltfRenderMetrics.Snapshot counts = GltfRenderMetrics.snapshot().minus(active.before);
        var buffered = GltfRenderMetrics.bufferedSnapshot().minus(active.bufferedBefore);
        var gpu = GltfGpuTiming.snapshot();
        long[] sorted = active.frameTimes.toLongArray();
        Arrays.sort(sorted);
        double frames = active.frames;
        String result = String.format(Locale.ROOT,
                "glTF real-scene profile | shaders %s | %,d frames | %.1f FPS | frame avg %.2f ms | p95 %.2f ms | p99 %.2f ms | API submit %.3f ms/frame | covered GPU %s | API draws %.1f/frame | instanced %.1f/frame | API uploads %.3f MiB/frame | CPU transformed %.1f vertices/frame | pose hits %.1f/frame | buffer reuses %.1f/frame | icon hits %.1f/frame | icon builds %.1f/frame | culled %.1f/frame | API requests %.1f/frame | GPU samples %,d | pending %d | dropped %,d",
                active.shadersOn && active.shadersOff ? "mixed" : active.shadersOn ? "on" : "off",
                active.frames, 1_000_000_000.0 * frames / active.frameNanos,
                active.frameNanos / frames / 1_000_000.0, percentile(sorted, 0.95), percentile(sorted, 0.99),
                counts.submitNanos() / frames / 1_000_000.0,
                gpu.supported() && gpu.samples() > 0 ? String.format(Locale.ROOT, "%.3f ms/frame", gpu.nanos() / frames / 1_000_000.0) : "unavailable",
                counts.draws() / frames, counts.instancedDraws() / frames,
                counts.uploadedBytes() / frames / 1_048_576.0, counts.transformedVertices() / frames,
                counts.poseCacheHits() / frames, counts.bufferReuses() / frames,
                counts.iconHits() / frames, counts.iconBuilds() / frames, counts.culled() / frames,
                counts.submissions() / frames, gpu.samples(), gpu.pending(), gpu.dropped());
        result += String.format(Locale.ROOT,
                " | caller-buffer bulk %.1f/frame | fallback %.1f/frame | copied %.3f MiB/frame | caller-buffer GPU/draws not measured",
                buffered.bulk() / frames, buffered.slow() / frames, buffered.bytes() / frames / 1_048_576.0);
        LogUtils.getLogger().info(result);
        message(result);
        GltfGpuTiming.clear();
        return 1;
    }

    private static double percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1,
                Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1))] / 1_000_000.0;
    }

    private static void message(String text) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.literal(text), false);
    }

    private static final class Session {
        private final int seconds;
        private final long started = System.nanoTime();
        private final LongArrayList frameTimes = new LongArrayList();
        private long measuredAt;
        private long lastFrame;
        private long frameNanos;
        private long frames;
        private GltfRenderMetrics.Snapshot before;
        private GltfRenderMetrics.BufferedSnapshot bufferedBefore;
        private boolean shadersOn;
        private boolean shadersOff;

        private Session(int seconds) {
            this.seconds = seconds;
        }
    }
}
