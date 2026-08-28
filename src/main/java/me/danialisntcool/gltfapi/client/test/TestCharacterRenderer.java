package me.danialisntcool.gltfapi.client.test;

import me.danialisntcool.gltfapi.GltfRendererApi;
import me.danialisntcool.gltfapi.api.client.GltfApi;
import me.danialisntcool.gltfapi.api.client.GltfModelHandle;
import me.danialisntcool.gltfapi.api.client.GltfRenderOptions;
import me.danialisntcool.gltfapi.api.client.render.GltfEntityRenderer;
import me.danialisntcool.gltfapi.test.TestCharacter;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class TestCharacterRenderer extends GltfEntityRenderer<TestCharacter> {
    private static final ResourceLocation MODEL_LOCATION = ResourceLocation.fromNamespaceAndPath(
            GltfRendererApi.MOD_ID, "models/gltf/test_character/scene.gltf");
    private static final ResourceLocation TEXTURE_LOCATION = ResourceLocation.fromNamespaceAndPath(
            GltfRendererApi.MOD_ID,
            "models/gltf/test_character/textures/mini_simple_material_secondary_basecolor.png");
    private static final GltfModelHandle MODEL = GltfApi.model(MODEL_LOCATION);

    public TestCharacterRenderer(EntityRendererProvider.Context context) {
        super(context, MODEL);
        shadowRadius = 0.45F;
    }

    @Override
    protected GltfRenderOptions renderOptions(TestCharacter entity, float yaw, float partialTick) {
        return new GltfRenderOptions(
                new Vector3f(),
                new Quaternionf().rotateY((float) Math.toRadians(-yaw)),
                new Vector3f(1.2F),
                entity.currentAnimation(),
                entity.animationTime(partialTick));
    }

    @Override
    public ResourceLocation getTextureLocation(TestCharacter entity) {
        return TEXTURE_LOCATION;
    }
}
