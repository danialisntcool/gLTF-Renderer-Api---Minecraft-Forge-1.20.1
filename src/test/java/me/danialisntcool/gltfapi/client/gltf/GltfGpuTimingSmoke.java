package me.danialisntcool.gltfapi.client.gltf;

import org.lwjgl.opengl.GL11;

public final class GltfGpuTimingSmoke {
    public static void verify() {
        GltfGpuTiming.start();
        try {
            if (!GltfGpuTiming.snapshot().supported()) {
                System.out.println("GPU timestamp check unavailable on this context");
                return;
            }
            int slot = GltfGpuTiming.begin();
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GltfGpuTiming.end(slot);
            GL11.glFlush();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (GltfGpuTiming.snapshot().pending() > 0 && System.nanoTime() < deadline) {
                GltfGpuTiming.poll();
                Thread.yield();
            }
            var result = GltfGpuTiming.snapshot();
            if (result.pending() != 0 || result.samples() != 1 || result.nanos() <= 0 || GL11.glGetError() != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("Nonblocking GPU timestamps failed: " + result);
            }
            System.out.println("Nonblocking GPU timestamp regression passed");
        } finally {
            GltfGpuTiming.clear();
        }
    }
}
