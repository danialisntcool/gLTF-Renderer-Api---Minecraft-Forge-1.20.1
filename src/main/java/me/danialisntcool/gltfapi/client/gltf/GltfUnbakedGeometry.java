package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.RenderTypeGroup;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;
import net.minecraftforge.client.model.pipeline.QuadBakingVertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public record GltfUnbakedGeometry(ResourceLocation model, String texture, boolean shade)
        implements IUnbakedGeometry<GltfUnbakedGeometry> {
    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                           Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState,
                           ItemOverrides overrides, ResourceLocation modelLocation) {
        Material material = resolveMaterial(context);
        TextureAtlasSprite sprite = spriteGetter.apply(material);
        List<BakedQuad> quads = new ArrayList<>();
        GltfModel loaded = GltfLoader.load(Minecraft.getInstance().getResourceManager(), model);
        try {
            Matrix4f root = context.getRootTransform().compose(modelState.getRotation()).getMatrix();
            for (GltfPrimitive primitive : loaded.primitives()) {
                if (primitive.skinIndex() >= 0 || primitive.morphTargetCount() > 0) {
                    throw new IllegalArgumentException("Baked glTF models cannot contain skins or morph targets: " + model);
                }
                for (Matrix4f instance : primitive.instances()) {
                    bakePrimitive(primitive, new Matrix4f(root).mul(primitive.transform()).mul(instance), sprite, quads);
                }
            }
        } finally {
            loaded.close();
        }
        Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
        RenderTypeGroup renderTypes = context.getRenderTypeHint() == null
                ? RenderTypeGroup.EMPTY : context.getRenderType(context.getRenderTypeHint());
        if (renderTypes.isEmpty()) {
            return new SimpleBakedModel(quads, culled, context.useAmbientOcclusion(), context.isGui3d(),
                    context.useBlockLight(), sprite, context.getTransforms(), overrides);
        }
        return new SimpleBakedModel(quads, culled, context.useAmbientOcclusion(), context.isGui3d(),
                context.useBlockLight(), sprite, context.getTransforms(), overrides, renderTypes);
    }

    private Material resolveMaterial(IGeometryBakingContext context) {
        if (context.hasMaterial(texture)) {
            return context.getMaterial(texture);
        }
        ResourceLocation direct = ResourceLocation.tryParse(texture);
        if (direct == null) {
            throw new IllegalArgumentException("Unknown glTF baked-model texture " + texture);
        }
        return new Material(InventoryMenu.BLOCK_ATLAS, direct);
    }

    private void bakePrimitive(GltfPrimitive primitive, Matrix4f transform,
                               TextureAtlasSprite sprite, List<BakedQuad> output) {
        int[] indices = primitive.indices();
        float[] positions = primitive.positions();
        float[] normals = primitive.normals();
        float[] uvs = primitive.material().baseColorTexture() != null
                && primitive.material().baseColorTexture().textureCoordinate() == 1
                ? primitive.secondaryTextureCoordinates() : primitive.textureCoordinates();
        float[] colors = primitive.colors();
        Matrix3f normalTransform = new Matrix3f(transform).invert().transpose();
        Vector3f faceNormal = new Vector3f();
        Vector3f transformedNormal = new Vector3f();
        for (int triangle = 0; triangle < indices.length; triangle += 3) {
            int first = indices[triangle];
            int second = indices[triangle + 1];
            int third = indices[triangle + 2];
            faceNormal.set(normals[first * 3], normals[first * 3 + 1], normals[first * 3 + 2])
                    .add(normals[second * 3], normals[second * 3 + 1], normals[second * 3 + 2])
                    .add(normals[third * 3], normals[third * 3 + 1], normals[third * 3 + 2]);
            normalTransform.transform(faceNormal);
            Direction direction = Direction.getNearest(faceNormal.x, faceNormal.y, faceNormal.z);
            QuadBakingVertexConsumer consumer = new QuadBakingVertexConsumer(output::add);
            consumer.setSprite(sprite);
            consumer.setDirection(direction);
            consumer.setShade(shade);
            consumer.setHasAmbientOcclusion(true);
            emitVertex(consumer, first, primitive, transform, normalTransform, positions, normals, uvs, colors, sprite);
            emitVertex(consumer, second, primitive, transform, normalTransform, positions, normals, uvs, colors, sprite);
            emitVertex(consumer, third, primitive, transform, normalTransform, positions, normals, uvs, colors, sprite);
            emitVertex(consumer, third, primitive, transform, normalTransform, positions, normals, uvs, colors, sprite);
        }
    }

    private void emitVertex(VertexConsumer consumer, int index, GltfPrimitive primitive,
                            Matrix4f transform, Matrix3f normalTransform, float[] positions,
                            float[] normals, float[] uvs, float[] colors, TextureAtlasSprite sprite) {
        int vectorOffset = index * 3;
        int colorOffset = index * 4;
        int uvOffset = index * 2;
        Vector3f position = transform.transformPosition(new Vector3f(
                positions[vectorOffset], positions[vectorOffset + 1], positions[vectorOffset + 2]));
        Vector3f normal = normalTransform.transform(new Vector3f(
                normals[vectorOffset], normals[vectorOffset + 1], normals[vectorOffset + 2])).normalize();
        consumer.vertex(position.x, position.y, position.z)
                .color(Math.round(colors[colorOffset] * primitive.material().red() * 255.0F),
                        Math.round(colors[colorOffset + 1] * primitive.material().green() * 255.0F),
                        Math.round(colors[colorOffset + 2] * primitive.material().blue() * 255.0F),
                        Math.round(colors[colorOffset + 3] * primitive.material().alpha() * 255.0F))
                .uv(sprite.getU(uvs[uvOffset] * 16.0F), sprite.getV(uvs[uvOffset + 1] * 16.0F))
                .normal(normal.x, normal.y, normal.z)
                .endVertex();
    }
}
