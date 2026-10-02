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
        GltfApi.renderWorld(model, new GltfRenderContext(
                poseStack,
                buffers,
                packedLight,
                packedOverlay,
                renderOptions(blockEntity, partialTick)));
    }

    protected GltfRenderOptions renderOptions(T blockEntity, float partialTick) {
        return GltfRenderOptions.DEFAULT;
    }

    public net.minecraft.world.phys.AABB renderBounds(T blockEntity, float partialTick) {
        GltfRenderOptions options = renderOptions(blockEntity, partialTick);
        var position = blockEntity.getBlockPos();
        org.joml.Matrix4f world = new org.joml.Matrix4f().translation(position.getX(), position.getY(), position.getZ())
                .mul(options.transformationMatrix());
        return GltfApi.bounds(model, options).map(bounds -> bounds.transformed(world))
                .orElseGet(() -> new net.minecraft.world.phys.AABB(position));
    }

    @Override
    public boolean shouldRender(T blockEntity, net.minecraft.world.phys.Vec3 camera) {
        var bounds = renderBounds(blockEntity, net.minecraft.client.Minecraft.getInstance().getFrameTime());
        double distance = getViewDistance();
        double configured = me.danialisntcool.gltfapi.client.GltfClientConfig.MODEL_RENDER_DISTANCE.get();
        if (configured > 0) distance = Math.min(distance, configured);
        boolean visible = camera.distanceToSqr(Math.max(bounds.minX, Math.min(camera.x, bounds.maxX)),
                Math.max(bounds.minY, Math.min(camera.y, bounds.maxY)),
                Math.max(bounds.minZ, Math.min(camera.z, bounds.maxZ))) < distance * distance;
        if (!visible) me.danialisntcool.gltfapi.client.gltf.GltfRenderMetrics.culled();
        return visible;
    }
}
