package me.danialisntcool.gltfapi.api.client;

import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.GltfRendererApi;
import me.danialisntcool.gltfapi.client.gltf.GltfModel;
import me.danialisntcool.gltfapi.client.gltf.GltfModelManager;
import me.danialisntcool.gltfapi.client.gltf.GltfRenderer;
import me.danialisntcool.gltfapi.client.gltf.GltfCompatibleBatch;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class GltfApi {
    private static final Logger LOGGER = LogUtils.getLogger();

    private GltfApi() {
    }

    public static GltfModelHandle model(ResourceLocation location) {
        return new GltfModelHandle(Objects.requireNonNull(location));
    }

    public static boolean isLoaded(GltfModelHandle handle) {
        return GltfModelManager.getInstance().getModel(handle.location()).isPresent();
    }

    public static Optional<GltfModelStatistics> statistics(GltfModelHandle handle) {
        return GltfModelManager.getInstance().getModel(handle.location()).map(GltfModel::statistics);
    }

    public static Optional<GltfBounds> bounds(GltfModelHandle handle) {
        return statistics(handle).map(statistics -> new GltfBounds(statistics.minimum(), statistics.maximum()));
    }

    public static boolean isVisible(GltfModelHandle handle, Frustum frustum, Matrix4f modelToWorld,
                                    GltfRenderOptions options) {
        Objects.requireNonNull(frustum);
        Objects.requireNonNull(modelToWorld);
        Objects.requireNonNull(options);
        return bounds(handle).map(bounds -> frustum.isVisible(bounds.transformed(
                new Matrix4f(modelToWorld).mul(options.transformationMatrix())))).orElse(false);
    }

    public static boolean renderLod(GltfLodGroup lodGroup, float distance, GltfRenderContext context) {
        Objects.requireNonNull(lodGroup);
        return render(lodGroup.select(distance), context);
    }

    public static Optional<String> loadFailure(GltfModelHandle handle) {
        return GltfModelManager.getInstance().getFailure(handle.location());
    }

    public static List<String> animationNames(GltfModelHandle handle) {
        return GltfModelManager.getInstance().getModel(handle.location())
                .map(GltfModel::animationNames)
                .orElseGet(List::of);
    }

    public static List<String> sceneNames(GltfModelHandle handle) {
        return GltfModelManager.getInstance().getModel(handle.location())
                .map(GltfModel::sceneNames)
                .orElseGet(List::of);
    }

    public static List<String> nodeNames(GltfModelHandle handle) {
        return GltfModelManager.getInstance().getModel(handle.location())
                .map(GltfModel::nodeNames)
                .orElseGet(List::of);
    }

    public static boolean render(GltfModelHandle handle, GltfRenderContext context) {
        return renderInternal(handle, context, false);
    }

    public static boolean renderBuffered(GltfModelHandle handle, GltfRenderContext context) {
        return renderInternal(handle, context, true);
    }

    private static boolean renderInternal(GltfModelHandle handle, GltfRenderContext context, boolean buffered) {
        Optional<GltfModel> model = GltfModelManager.getInstance().getModel(handle.location());
        if (model.isEmpty()) {
            return false;
        }
        if (!isContextVisible(context, model.get())) {
            return false;
        }
        context.poseStack().pushPose();
        try {
            context.options().apply(context.poseStack());
            if (buffered) {
                GltfRenderer.renderBuffered(
                        model.get(),
                        context.poseStack(),
                        context.buffers(),
                        context.packedLight(),
                        context.packedOverlay(),
                        context.options());
            } else {
                GltfRenderer.render(
                        model.get(),
                        context.poseStack(),
                        context.buffers(),
                        context.packedLight(),
                        context.packedOverlay(),
                        context.options());
            }
        } catch (RuntimeException | LinkageError exception) {
            throw renderFailure("model " + handle.location(), exception);
        } finally {
            context.poseStack().popPose();
        }
        return true;
    }

    public static int renderBatch(Iterable<GltfRenderRequest> requests) {
        Objects.requireNonNull(requests);
        if (!GltfRenderer.usesCompatibilityPath()) {
            int rendered = 0;
            for (GltfRenderRequest request : requests) {
                if (render(request.model(), request.context())) {
                    rendered++;
                }
            }
            return rendered;
        }
        GltfCompatibleBatch batch = new GltfCompatibleBatch(GltfRenderer.isRenderingShadowPass());
        int rendered = 0;
        for (GltfRenderRequest request : requests) {
            Optional<GltfModel> model = GltfModelManager.getInstance().getModel(request.model().location());
            if (model.isEmpty() || !isContextVisible(request.context(), model.get())) {
                continue;
            }
            GltfRenderContext context = request.context();
            context.poseStack().pushPose();
            try {
                context.options().apply(context.poseStack());
                batch.add(model.get(), new Matrix4f(context.poseStack().last().pose()),
                        new Matrix3f(context.poseStack().last().normal()), context.buffers(),
                        context.packedLight(), context.packedOverlay(), context.options());
            } catch (RuntimeException | LinkageError exception) {
                throw renderFailure("model " + request.model().location() + " in a batch", exception);
            } finally {
                context.poseStack().popPose();
            }
            rendered++;
        }
        try {
            batch.flush();
        } catch (RuntimeException | LinkageError exception) {
            throw renderFailure("model batch", exception);
        }
        return rendered;
    }

    public static void renderRequired(GltfModelHandle handle, GltfRenderContext context) {
        GltfModel model = GltfModelManager.getInstance().requireModel(handle.location());
        if (!isContextVisible(context, model)) {
            return;
        }
        context.poseStack().pushPose();
        try {
            context.options().apply(context.poseStack());
            GltfRenderer.render(
                    model,
                    context.poseStack(),
                    context.buffers(),
                    context.packedLight(),
                    context.packedOverlay(),
                    context.options());
        } catch (RuntimeException | LinkageError exception) {
            throw renderFailure("required model " + handle.location(), exception);
        } finally {
            context.poseStack().popPose();
        }
    }

    private static boolean isContextVisible(GltfRenderContext context, GltfModel model) {
        if (context.frustum() == null || !context.options().nodeRotationOffsets().isEmpty()) {
            return true;
        }
        GltfModelStatistics statistics = model.statistics();
        GltfBounds bounds = new GltfBounds(statistics.minimum(), statistics.maximum());
        Matrix4f transform = context.modelToWorld().mul(context.options().transformationMatrix());
        return context.frustum().isVisible(bounds.transformed(transform));
    }

    private static IllegalStateException renderFailure(String subject, Throwable cause) {
        String message = "Failed to render glTF " + subject + " | Support: " + GltfRendererApi.SUPPORT_URL;
        LOGGER.error(message, cause);
        return new IllegalStateException(message, cause);
    }
}
