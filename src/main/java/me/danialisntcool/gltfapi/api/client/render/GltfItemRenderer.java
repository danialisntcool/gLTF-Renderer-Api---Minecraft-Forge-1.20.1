package me.danialisntcool.gltfapi.api.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import me.danialisntcool.gltfapi.client.gltf.GltfGuiIconCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class GltfItemRenderer extends BlockEntityWithoutLevelRenderer {
    private final GltfModelHandle model;

    public GltfItemRenderer(GltfModelHandle model) {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        this.model = model;
    }

    @Override
    public final void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                                   MultiBufferSource buffers, int packedLight, int packedOverlay) {
        GltfRenderContext context = new GltfRenderContext(
                poseStack,
                buffers,
                packedLight,
                packedOverlay,
                renderOptions(stack, displayContext));
        if (displayContext == ItemDisplayContext.GUI && GltfGuiIconCache.render(model, context)) return;
        GltfApi.renderBuffered(model, context);
    }

    protected GltfRenderOptions renderOptions(ItemStack stack, ItemDisplayContext displayContext) {
        return GltfRenderOptions.DEFAULT;
    }
}
