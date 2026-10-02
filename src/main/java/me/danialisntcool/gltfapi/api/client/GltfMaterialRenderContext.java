package me.danialisntcool.gltfapi.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import me.danialisntcool.gltfapi.client.gltf.GltfMaterial;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

@OnlyIn(Dist.CLIENT)
public record GltfMaterialRenderContext(
        ResourceLocation model,
        GltfMaterial material,
        FloatBuffer positions,
        FloatBuffer normals,
        FloatBuffer colors,
        FloatBuffer primaryUvs,
        FloatBuffer secondaryUvs,
        IntBuffer indices,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight,
        int packedOverlay,
        boolean shaderPack,
        boolean shadowPass
) {
    public GltfMaterialRenderContext {
        positions = positions.asReadOnlyBuffer();
        normals = normals.asReadOnlyBuffer();
        colors = colors.asReadOnlyBuffer();
        primaryUvs = primaryUvs.asReadOnlyBuffer();
        secondaryUvs = secondaryUvs.asReadOnlyBuffer();
        indices = indices.asReadOnlyBuffer();
    }
}
