package me.danialisntcool.gltfapi.client.gltf;

public final class GltfRenderMetrics {
    private static long draws;
    private static long instancedDraws;
    private static long uploadedBytes;
    private static long transformedVertices;
    private static long streamedVertices;
    private static long poseCacheHits;
    private static long bufferReuses;
    private static long iconHits;
    private static long iconBuilds;
    private static long culled;
    private static long submissions;
    private static long submitNanos;
    private static int depth;
    private static long bufferedBulk;
    private static long bufferedSlow;
    private static long bufferedBytes;

    static void buffered(boolean bulk, long bytes) {
        if (bulk) bufferedBulk++; else bufferedSlow++;
        bufferedBytes += bytes;
    }

    public static BufferedSnapshot bufferedSnapshot() {
        return new BufferedSnapshot(bufferedBulk, bufferedSlow, bufferedBytes);
    }

    public record BufferedSnapshot(long bulk, long slow, long bytes) {
        public BufferedSnapshot minus(BufferedSnapshot before) {
            return new BufferedSnapshot(bulk - before.bulk, slow - before.slow, bytes - before.bytes);
        }
    }

    public static long beginSubmission() {
        return depth++ == 0 ? System.nanoTime() : 0;
    }

    public static void endSubmission(long start) {
        depth--;
        if (start != 0) submitNanos += System.nanoTime() - start;
    }

    private GltfRenderMetrics() {
    }

    static void draw(int instances) {
        draws++;
        if (instances > 1) instancedDraws++;
    }

    static void upload(long bytes) {
        uploadedBytes += bytes;
    }

    static void stream(int transformed, int submitted) {
        transformedVertices += transformed;
        streamedVertices += submitted;
    }

    static void poseHit() {
        poseCacheHits++;
    }

    static void reuse() {
        bufferReuses++;
    }

    static void icon(boolean hit) {
        if (hit) iconHits++; else iconBuilds++;
    }

    public static void culled() { culled++; }

    public static void submit() { submissions++; }

    public static Snapshot snapshot() {
        return new Snapshot(draws, instancedDraws, uploadedBytes, transformedVertices,
                streamedVertices, poseCacheHits, bufferReuses, iconHits, iconBuilds, culled, submissions, submitNanos);
    }

    public record Snapshot(long draws, long instancedDraws, long uploadedBytes, long transformedVertices,
                           long streamedVertices, long poseCacheHits, long bufferReuses,
                           long iconHits, long iconBuilds, long culled, long submissions, long submitNanos) {
        public Snapshot minus(Snapshot before) {
            return new Snapshot(draws - before.draws, instancedDraws - before.instancedDraws,
                    uploadedBytes - before.uploadedBytes, transformedVertices - before.transformedVertices,
                    streamedVertices - before.streamedVertices, poseCacheHits - before.poseCacheHits,
                    bufferReuses - before.bufferReuses, iconHits - before.iconHits,
                    iconBuilds - before.iconBuilds, culled - before.culled, submissions - before.submissions,
                    submitNanos - before.submitNanos);
        }
    }
}
