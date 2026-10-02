package me.danialisntcool.gltfapi.api.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public abstract class GltfEntityRenderer<T extends Entity> extends EntityRenderer<T> {
    private final GltfModelHandle model;

    protected GltfEntityRenderer(EntityRendererProvider.Context context, GltfModelHandle model) {
        super(context);
        this.model = model;
    }

    @Override
    public final void render(T entity, float yaw, float partialTick, PoseStack poseStack,
                             MultiBufferSource buffers, int packedLight) {
        GltfApi.renderWorld(model, new GltfRenderContext(
                poseStack,
                buffers,
                packedLight,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                renderOptions(entity, yaw, partialTick)));
        super.render(entity, yaw, partialTick, poseStack, buffers, packedLight);
    }

    protected GltfRenderOptions renderOptions(T entity, float yaw, float partialTick) {
        return GltfRenderOptions.DEFAULT;
    }

    @Override
    public boolean shouldRender(T entity, net.minecraft.client.renderer.culling.Frustum frustum,
                                double cameraX, double cameraY, double cameraZ) {
        if (!entity.shouldRender(cameraX, cameraY, cameraZ)) return false;
        if (entity.noCulling || me.danialisntcool.gltfapi.client.gltf.GltfRenderer.isRenderingShadowPass()) return true;
        float partial = net.minecraft.client.Minecraft.getInstance().getFrameTime();
        float yaw = net.minecraft.util.Mth.rotLerp(partial, entity.yRotO, entity.getYRot());
        GltfRenderOptions options = renderOptions(entity, yaw, partial);
        var offset = getRenderOffset(entity, partial);
        org.joml.Matrix4f world = new org.joml.Matrix4f().translation(
                (float) (net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX()) + offset.x),
                (float) (net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY()) + offset.y),
                (float) (net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ()) + offset.z));
        boolean visible = GltfApi.bounds(model, options).map(bounds -> frustum.isVisible(
                bounds.transformed(world.mul(options.transformationMatrix())).inflate(0.01))).orElse(true);
        if (!visible) me.danialisntcool.gltfapi.client.gltf.GltfRenderMetrics.culled();
        return visible;
    }

    @Override
    public abstract ResourceLocation getTextureLocation(T entity);
}
