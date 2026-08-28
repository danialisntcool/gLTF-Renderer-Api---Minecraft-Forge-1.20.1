package me.danialisntcool.gltfapi.api.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderContext;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public abstract class GltfParticle extends Particle {
    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(65536));
    private final GltfModelHandle model;

    protected GltfParticle(ClientLevel level, double x, double y, double z, GltfModelHandle model) {
        super(level, x, y, z);
        this.model = model;
    }

    protected GltfParticle(ClientLevel level, double x, double y, double z,
                           double velocityX, double velocityY, double velocityZ,
                           GltfModelHandle model) {
        super(level, x, y, z, velocityX, velocityY, velocityZ);
        this.model = model;
    }

    @Override
    public final void render(VertexConsumer ignored, Camera camera, float partialTick) {
        Vec3 cameraPosition = camera.getPosition();
        double renderX = Mth.lerp(partialTick, xo, x) - cameraPosition.x;
        double renderY = Mth.lerp(partialTick, yo, y) - cameraPosition.y;
        double renderZ = Mth.lerp(partialTick, zo, z) - cameraPosition.z;
        PoseStack poseStack = new PoseStack();
        poseStack.translate(renderX, renderY, renderZ);
        if (billboard()) {
            poseStack.mulPose(camera.rotation());
        }
        GltfApi.render(model, new GltfRenderContext(poseStack, BUFFERS, getLightColor(partialTick),
                OverlayTexture.NO_OVERLAY, renderOptions(partialTick)));
        BUFFERS.endBatch();
    }

    @Override
    public final ParticleRenderType getRenderType() {
        return ParticleRenderType.CUSTOM;
    }

    protected boolean billboard() {
        return false;
    }

    protected GltfRenderOptions renderOptions(float partialTick) {
        return GltfRenderOptions.DEFAULT;
    }
}
