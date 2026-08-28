package me.danialisntcool.gltfapi.api.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public abstract class GltfArmorLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private final GltfModelHandle model;

    protected GltfArmorLayer(RenderLayerParent<T, M> parent, GltfModelHandle model) {
        super(parent);
        this.model = model;
    }

    @Override
    public final void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, T entity,
                             float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                             float netHeadYaw, float headPitch) {
        if (!shouldRender(entity)) {
            return;
        }
        GltfApi.render(model, new GltfRenderContext(poseStack, buffers, packedLight,
                LivingEntityRenderer.getOverlayCoords(entity, 0.0F),
                renderOptions(entity, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch)));
    }

    protected boolean shouldRender(T entity) {
        return true;
    }

    protected GltfRenderOptions renderOptions(T entity, float limbSwing, float limbSwingAmount,
                                               float partialTick, float ageInTicks,
                                               float netHeadYaw, float headPitch) {
        return GltfRenderOptions.DEFAULT;
    }
}
