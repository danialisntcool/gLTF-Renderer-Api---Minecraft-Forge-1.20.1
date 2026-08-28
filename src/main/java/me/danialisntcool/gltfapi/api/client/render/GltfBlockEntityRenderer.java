package me.danialisntcool.gltfapi.api.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public abstract class GltfBlockEntityRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
    private final GltfModelHandle model;

    protected GltfBlockEntityRenderer(GltfModelHandle model) {
        this.model = model;
    }

    @Override
    public final void render(T blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                             int packedLight, int packedOverlay) {
        GltfApi.render(model, new GltfRenderContext(
                poseStack,
                buffers,
                packedLight,
                packedOverlay,
                renderOptions(blockEntity, partialTick)));
    }

    protected GltfRenderOptions renderOptions(T blockEntity, float partialTick) {
        return GltfRenderOptions.DEFAULT;
    }
}
