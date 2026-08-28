package me.danialisntcool.gltfapi.client.gltf;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.geometry.IGeometryLoader;

public final class GltfGeometryLoader implements IGeometryLoader<GltfUnbakedGeometry> {
    public static final GltfGeometryLoader INSTANCE = new GltfGeometryLoader();

    private GltfGeometryLoader() {
    }

    @Override
    public GltfUnbakedGeometry read(JsonObject json, JsonDeserializationContext context) throws JsonParseException {
        if (!json.has("model")) {
            throw new JsonParseException("glTF model JSON requires a model resource location");
        }
        ResourceLocation model = ResourceLocation.tryParse(json.get("model").getAsString());
        if (model == null) {
            throw new JsonParseException("Invalid glTF model resource location");
        }
        String texture = json.has("texture") ? json.get("texture").getAsString() : "particle";
        boolean shade = !json.has("shade") || json.get("shade").getAsBoolean();
        return new GltfUnbakedGeometry(model, texture, shade);
    }
}
