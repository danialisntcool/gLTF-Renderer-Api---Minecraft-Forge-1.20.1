package me.danialisntcool.gltfapi.client.gltf;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

public final class GltfGpuTiming {
    private static final int CAPACITY = 64;
    private static final int[] START = new int[CAPACITY];
    private static final int[] END = new int[CAPACITY];
    private static final boolean[] PENDING = new boolean[CAPACITY];
    private static boolean enabled;
    private static boolean supported;
    private static long nanos;
    private static long samples;
    private static long dropped;
    private static int cursor;

    private GltfGpuTiming() {
    }

    public static void start() {
        clear();
        supported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
        enabled = supported;
    }

    static int begin() {
        if (!enabled) return -1;
        int slot = cursor;
        cursor = (cursor + 1) % CAPACITY;
        if (PENDING[slot]) {
            dropped++;
            return -1;
        }
        if (START[slot] == 0) {
            START[slot] = GL15.glGenQueries();
            END[slot] = GL15.glGenQueries();
        }
        GL33.glQueryCounter(START[slot], GL33.GL_TIMESTAMP);
        PENDING[slot] = true;
        return slot;
    }

    static void end(int slot) {
        if (slot >= 0) GL33.glQueryCounter(END[slot], GL33.GL_TIMESTAMP);
    }

    public static void poll() {
        if (!supported) return;
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (PENDING[slot] && GL15.glGetQueryObjecti(END[slot], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long duration = GL33.glGetQueryObjectui64(END[slot], GL15.GL_QUERY_RESULT)
                        - GL33.glGetQueryObjectui64(START[slot], GL15.GL_QUERY_RESULT);
                nanos += Math.max(0, duration);
                samples++;
                PENDING[slot] = false;
            }
        }
    }

    public static Snapshot snapshot() {
        int pending = 0;
        for (boolean value : PENDING) if (value) pending++;
        return new Snapshot(supported, nanos, samples, pending, dropped);
    }

    public static void stop() {
        enabled = false;
        poll();
    }

    public static void clear() {
        enabled = false;
        for (int slot = 0; slot < CAPACITY; slot++) {
            if (START[slot] != 0) GL15.glDeleteQueries(START[slot]);
            if (END[slot] != 0) GL15.glDeleteQueries(END[slot]);
            START[slot] = 0;
            END[slot] = 0;
            PENDING[slot] = false;
        }
        nanos = samples = dropped = 0;
        cursor = 0;
        supported = false;
    }

    public record Snapshot(boolean supported, long nanos, long samples, int pending, long dropped) {
    }
}
