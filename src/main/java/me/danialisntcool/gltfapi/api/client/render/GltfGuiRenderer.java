package me.danialisntcool.gltfapi.api.client.render;

import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class GltfGuiRenderer {
    private GltfGuiRenderer() {
    }

    public static boolean render(GuiGraphics graphics, GltfModelHandle model,
                                 float x, float y, float depth, float scale) {
        return render(graphics, model, x, y, depth, scale, GltfRenderOptions.DEFAULT);
    }

    public static boolean render(GuiGraphics graphics, GltfModelHandle model,
                                 float x, float y, float depth, float scale, GltfRenderOptions options) {
        graphics.flush();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, depth);
            graphics.pose().scale(scale, -scale, scale);
            boolean rendered = GltfApi.renderBuffered(model, new GltfRenderContext(
                    graphics.pose(), graphics.bufferSource(), LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, options));
            graphics.flush();
            return rendered;
        } finally {
            graphics.pose().popPose();
        }
    }
}
