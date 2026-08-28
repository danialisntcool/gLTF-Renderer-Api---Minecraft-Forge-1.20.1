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
        GltfApi.render(model, new GltfRenderContext(
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
    public abstract ResourceLocation getTextureLocation(T entity);
}
