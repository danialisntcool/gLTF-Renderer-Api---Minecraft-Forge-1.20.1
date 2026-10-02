package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import me.danialisntcool.gltfapi.api.client.GltfBounds;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderMode;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraft.client.Minecraft;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GltfGuiIconCache {
    private static final Map<Key, TextureTarget> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final BufferBuilder STAGING = new BufferBuilder(4096);
    private static final IconBuffers BUFFERS = new IconBuffers();
    private static long bytes;
    private static int builds;

    private GltfGuiIconCache() {
    }

    public static void beginFrame() {
        builds = 0;
    }

    public static boolean render(GltfModelHandle handle, GltfRenderContext context) {
        long start = GltfRenderMetrics.beginSubmission();
        try {
            return renderIcon(handle, context);
        } finally {
            GltfRenderMetrics.endSubmission(start);
        }
    }

    private static boolean renderIcon(GltfModelHandle handle, GltfRenderContext context) {
        RenderSystem.assertOnRenderThread();
        GltfModel model = GltfModelManager.getInstance().getModel(handle.location()).orElse(null);
        float[] color = RenderSystem.getShaderColor();
        if (model == null || !model.supportsIconCache(context.options())
                || GltfRenderer.isShaderPackInUse() || !GltfRenderer.isOrthographic(RenderSystem.getProjectionMatrix())
                || color[0] != 1 || color[1] != 1 || color[2] != 1 || color[3] != 1
                || !me.danialisntcool.gltfapi.client.GltfClientConfig.ICON_CACHE.get()) return false;
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.poseStack().last().pose());
        Matrix4f options = context.options().transformationMatrix();
        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(view).mul(options);
        Rectangle rectangle = project(model.renderBounds(context.options()), mvp);
        if (rectangle == null) return false;
        int screenWidth = Minecraft.getInstance().getWindow().getWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getHeight();
        rectangle = rectangle.padded(2.0F / screenWidth, 2.0F / screenHeight);
        int width = Math.max(8, Math.min(ModMetadata.ICON_MAX_RESOLUTION,
                (int) Math.ceil((rectangle.right - rectangle.left) * screenWidth * 0.5F)));
        int height = Math.max(8, Math.min(ModMetadata.ICON_MAX_RESOLUTION,
                (int) Math.ceil((rectangle.top - rectangle.bottom) * screenHeight * 0.5F)));
        Matrix4f linear = new Matrix4f(view).m30(0).m31(0).m32(0);
        var shader = GltfShaders.bufferedModelShader();
        RenderSystem.setupShaderLights(shader);
        var light0 = shader.LIGHT0_DIRECTION.getFloatBuffer();
        var light1 = shader.LIGHT1_DIRECTION.getFloatBuffer();
        Key key = new Key(handle.location(), linear, options, context.options().scene(),
                context.packedLight(), context.packedOverlay(), context.renderMode(), width, height,
                GltfRenderer.usesBufferedPbr(context.renderMode()),
                new Vector3f(light0.get(0), light0.get(1), light0.get(2)),
                new Vector3f(light1.get(0), light1.get(1), light1.get(2)));
        TextureTarget target = CACHE.get(key);
        if (target == null) {
            if (builds >= ModMetadata.ICON_BUILDS_PER_FRAME || ModMetadata.ICON_CACHE_ENTRIES <= 0
                    || (long) width * height * 8 > ModMetadata.ICON_CACHE_BYTES) return false;
            builds++;
            target = capture(handle, context, rectangle, width, height);
            CACHE.put(key, target);
            bytes += (long) width * height * 8;
            trim();
            GltfRenderMetrics.icon(false);
        } else {
            GltfRenderMetrics.icon(true);
        }
        draw(target, rectangle);
        return true;
    }

    static Rectangle project(GltfBounds bounds, Matrix4f mvp) {
        Vector3f min = bounds.minimum();
        Vector3f max = bounds.maximum();
        Vector3f projectedMin = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f projectedMax = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f corner = new Vector3f();
        for (int bits = 0; bits < 8; bits++) {
            corner.set((bits & 1) == 0 ? min.x : max.x, (bits & 2) == 0 ? min.y : max.y,
                    (bits & 4) == 0 ? min.z : max.z);
            mvp.transformProject(corner);
            projectedMin.min(corner);
            projectedMax.max(corner);
        }
        if (!projectedMin.isFinite() || !projectedMax.isFinite()
                || projectedMax.x - projectedMin.x < 0.000001F || projectedMax.y - projectedMin.y < 0.000001F
                || projectedMin.z < -1 || projectedMax.z > 1) return null;
        return new Rectangle(projectedMin.x, projectedMax.x, projectedMin.y, projectedMax.y, projectedMin.z);
    }

    private static TextureTarget capture(GltfModelHandle handle, GltfRenderContext context,
                                         Rectangle rectangle, int width, int height) {
        try (CaptureState ignored = new CaptureState(true)) {
            TextureTarget target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            try {
                target.setClearColor(0, 0, 0, 0);
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                Matrix4f crop = new Matrix4f().translation(
                        -(rectangle.left + rectangle.right) / (rectangle.right - rectangle.left),
                        -(rectangle.bottom + rectangle.top) / (rectangle.top - rectangle.bottom), 0)
                        .scale(2 / (rectangle.right - rectangle.left), 2 / (rectangle.top - rectangle.bottom), 1);
                RenderSystem.setProjectionMatrix(crop.mul(RenderSystem.getProjectionMatrix()), VertexSorting.ORTHOGRAPHIC_Z);
                GltfApi.renderBuffered(handle, new GltfRenderContext(context.poseStack(), BUFFERS,
                        context.packedLight(), context.packedOverlay(), context.options()).withRenderMode(context.renderMode()));
                BUFFERS.endBatch();
                target.setFilterMode(GL11.GL_LINEAR);
                return target;
            } catch (RuntimeException | LinkageError exception) {
                BUFFERS.abort();
                target.destroyBuffers();
                throw exception;
            }
        }
    }

    private static void draw(TextureTarget target, Rectangle rectangle) {
        try (CaptureState ignored = new CaptureState(false)) {
            RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShader(GltfShaders::iconShader);
            RenderSystem.setShaderTexture(0, target.getColorTextureId());
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.vertex(rectangle.left, rectangle.bottom, rectangle.depth).uv(0, 0).endVertex();
            builder.vertex(rectangle.right, rectangle.bottom, rectangle.depth).uv(1, 0).endVertex();
            builder.vertex(rectangle.right, rectangle.top, rectangle.depth).uv(1, 1).endVertex();
            builder.vertex(rectangle.left, rectangle.top, rectangle.depth).uv(0, 1).endVertex();
            int timing = GltfGpuTiming.begin();
            try {
                BufferUploader.drawWithShader(builder.end());
                GltfRenderMetrics.draw(1);
            } finally {
                GltfGpuTiming.end(timing);
            }
        }
    }

    private static void trim() {
        while (CACHE.size() > ModMetadata.ICON_CACHE_ENTRIES || bytes > ModMetadata.ICON_CACHE_BYTES) {
            var iterator = CACHE.entrySet().iterator();
            TextureTarget target = iterator.next().getValue();
            iterator.remove();
            bytes -= (long) target.width * target.height * 8;
            target.destroyBuffers();
        }
    }

    public static void clear() {
        for (TextureTarget target : CACHE.values()) target.destroyBuffers();
        CACHE.clear();
        bytes = 0;
        builds = 0;
    }

    record Rectangle(float left, float right, float bottom, float top, float depth) {
        Rectangle padded(float x, float y) {
            return new Rectangle(left - x, right + x, bottom - y, top + y, depth);
        }
    }

    private record Key(ResourceLocation model, Matrix4f pose, Matrix4f options, String scene,
                       int light, int overlay, GltfRenderMode mode, int width, int height, boolean pbr,
                       Vector3f light0, Vector3f light1) {
    }

    private static final class CaptureState implements AutoCloseable {
        private final GltfNativeState nativeState;
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final VertexSorting sorting = RenderSystem.getVertexSorting();
        private final int drawTarget;
        private final int readTarget;
        private final int[] viewport = new int[4];
        private final boolean scissor;
        private final float[] clearColor = new float[4];
        private final double clearDepth;
        private final boolean capture;

        private CaptureState(boolean capture) {
            this.capture = capture;
            nativeState = new GltfNativeState(capture ? 9 : 1);
            drawTarget = capture ? GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) : 0;
            readTarget = capture ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) : 0;
            scissor = capture && GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            clearDepth = capture ? GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE) : 1;
            if (capture) try (MemoryStack stack = MemoryStack.stackPush()) {
                var values = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, values);
                values.get(viewport);
                var colors = stack.mallocFloat(4);
                GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, colors);
                colors.get(clearColor);
            }
            RenderSystem.getModelViewStack().pushPose();
            if (capture) RenderSystem.disableScissor();
        }

        @Override
        public void close() {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            if (capture) {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (scissor) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
            GlStateManager._clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            GlStateManager._clearDepth(clearDepth);
            }
            nativeState.close();
        }
    }

    private static final class IconBuffers extends MultiBufferSource.BufferSource {
        private IconBuffers() {
            super(STAGING, Map.of());
        }

        private void abort() {
            if (STAGING.building()) STAGING.end().release();
            STAGING.discard();
            lastState = java.util.Optional.empty();
            startedBuffers.clear();
        }
    }
}
