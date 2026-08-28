package me.danialisntcool.gltfapi.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GltfRenderTypes extends RenderStateShard {
    private static final Map<Key, RenderType> CACHE = new ConcurrentHashMap<>();

    private GltfRenderTypes() {
        super("gltf_render_types", () -> {}, () -> {});
    }

    public static RenderType opaque(ResourceLocation texture, boolean doubleSided) {
        return CACHE.computeIfAbsent(new Key(texture, Alpha.OPAQUE, doubleSided), GltfRenderTypes::create);
    }

    public static RenderType cutout(ResourceLocation texture, boolean doubleSided) {
        return CACHE.computeIfAbsent(new Key(texture, Alpha.CUTOUT, doubleSided), GltfRenderTypes::create);
    }

    public static RenderType translucent(ResourceLocation texture, boolean doubleSided) {
        return CACHE.computeIfAbsent(new Key(texture, Alpha.TRANSLUCENT, doubleSided), GltfRenderTypes::create);
    }

    private static RenderType create(Key key) {
        RenderType.CompositeState.CompositeStateBuilder builder = RenderType.CompositeState.builder()
                .setTextureState(new TextureStateShard(key.texture(), false, false))
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setCullState(key.doubleSided() ? NO_CULL : CULL);
        switch (key.alpha()) {
            case OPAQUE -> builder
                    .setShaderState(RENDERTYPE_ENTITY_SOLID_SHADER)
                    .setTransparencyState(NO_TRANSPARENCY);
            case CUTOUT -> builder
                    .setShaderState(key.doubleSided()
                            ? RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER : RENDERTYPE_ENTITY_CUTOUT_SHADER)
                    .setTransparencyState(NO_TRANSPARENCY);
            case TRANSLUCENT -> builder
                    .setShaderState(key.doubleSided()
                            ? RENDERTYPE_ENTITY_TRANSLUCENT_SHADER : RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY);
        }
        return RenderType.create("gltf_" + key.alpha().name().toLowerCase(),
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1536,
                true, false, builder.createCompositeState(true));
    }

    private enum Alpha {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT
    }

    private record Key(ResourceLocation texture, Alpha alpha, boolean doubleSided) {
    }
}
