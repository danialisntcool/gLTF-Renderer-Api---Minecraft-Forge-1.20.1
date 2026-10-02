package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.GltfRendererApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GltfModelManager extends SimplePreparableReloadListener<GltfModelManager.ReloadData> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final GltfModelManager INSTANCE = new GltfModelManager();
    private volatile Map<ResourceLocation, GltfModel> models = Map.of();
    private volatile Map<ResourceLocation, String> failures = Map.of();
    private Set<ResourceLocation> dynamicTextures = Set.of();

    private GltfModelManager() {
    }

    public static GltfModelManager getInstance() {
        return INSTANCE;
    }

    public Optional<GltfModel> getModel(ResourceLocation location) {
        return Optional.ofNullable(models.get(location));
    }

    public Optional<String> getFailure(ResourceLocation location) {
        return Optional.ofNullable(failures.get(location));
    }

    public GltfModel requireModel(ResourceLocation location) {
        GltfModel model = models.get(location);
        if (model == null) {
            String reason = failures.get(location);
            String message = reason == null
                    ? "glTF model is not loaded: " + location
                    : "glTF model failed to load: " + location + " | " + reason;
            if (!message.contains(GltfRendererApi.SUPPORT_URL)) {
                message += " | Support: " + GltfRendererApi.SUPPORT_URL;
            }
            throw new IllegalStateException(message);
        }
        return model;
    }

    @Override
    protected ReloadData prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, GltfModel> loaded = new HashMap<>();
        Map<ResourceLocation, String> failed = new HashMap<>();
        Set<ResourceLocation> resources = resourceManager.listResources("models/gltf", location -> {
            String path = location.getPath().toLowerCase(java.util.Locale.ROOT);
            return path.endsWith(".gltf") || path.endsWith(".glb");
        }).keySet();
        LOGGER.info("Discovered {} glTF model resources", resources.size());
        resources.forEach(location -> {
            try {
                loaded.put(location, GltfLoader.load(resourceManager, location));
            } catch (GltfLoadException exception) {
                failed.put(location, exception.getMessage());
                LOGGER.error("Could not load glTF model {}. Support: {}",
                        location, GltfRendererApi.SUPPORT_URL, exception);
            }
        });
        return new ReloadData(Map.copyOf(loaded), Map.copyOf(failed));
    }

    @Override
    protected void apply(ReloadData reloadData, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        GltfRenderer.clearStateCaches();
        textureManager.register(GltfEnvironmentTexture.LOCATION, new GltfEnvironmentTexture());
        for (GltfModel model : models.values()) {
            model.close();
        }
        for (ResourceLocation texture : dynamicTextures) {
            textureManager.release(texture);
        }
        Set<ResourceLocation> registered = new HashSet<>();
        Map<ResourceLocation, GltfModel> validModels = new HashMap<>();
        Map<ResourceLocation, String> failed = new HashMap<>(reloadData.failures());
        for (Map.Entry<ResourceLocation, GltfModel> entry : reloadData.models().entrySet()) {
            boolean valid = true;
            for (Map.Entry<ResourceLocation, GltfEmbeddedTexture> texture : entry.getValue().embeddedTextures().entrySet()) {
                try {
                    registerTexture(textureManager, texture.getKey(), texture.getValue());
                    registered.add(texture.getKey());
                } catch (IOException exception) {
                    valid = false;
                    failed.put(entry.getKey(), "Could not decode embedded texture " + texture.getKey() + ": " + exception.getMessage());
                    LOGGER.error("Could not decode embedded texture {} for glTF model {}. Support: {}",
                            texture.getKey(), entry.getKey(), GltfRendererApi.SUPPORT_URL, exception);
                }
            }
            if (valid) {
                try {
                    entry.getValue().upload();
                    validModels.put(entry.getKey(), entry.getValue());
                } catch (RuntimeException exception) {
                    entry.getValue().close();
                    failed.put(entry.getKey(), "Could not upload model to the GPU: " + exception.getMessage());
                    LOGGER.error("Could not upload glTF model {} to the GPU. Support: {}",
                            entry.getKey(), GltfRendererApi.SUPPORT_URL, exception);
                }
            } else {
                entry.getValue().close();
            }
        }
        dynamicTextures = Set.copyOf(registered);
        models = Map.copyOf(validModels);
        failures = Map.copyOf(failed);
        LOGGER.info("Loaded {} glTF models; {} failed", models.size(), failures.size());
        if (!failures.isEmpty()) {
            LOGGER.warn("Some glTF models failed to load. Support: {}", GltfRendererApi.SUPPORT_URL);
        }
    }

    private void registerTexture(TextureManager textureManager, ResourceLocation location,
                                 GltfEmbeddedTexture embedded) throws IOException {
        GltfImageData primary = embedded.primary();
        if (primary.ktx2()) {
            try {
                KtxTexture texture = new KtxTexture(primary.bytes());
                textureManager.register(location, texture);
                texture.load(Minecraft.getInstance().getResourceManager());
                return;
            } catch (IOException | RuntimeException | LinkageError exception) {
                if (embedded.fallback() == null) {
                    throw new IOException("KTX2 texture has no usable fallback", exception);
                }
                LOGGER.warn("KTX2 texture {} failed; using its glTF fallback source. Support: {}",
                        location, GltfRendererApi.SUPPORT_URL, exception);
            }
        }
        GltfImageData selected = primary.ktx2() ? embedded.fallback() : primary;
        NativeImage image = NativeImage.read(new ByteArrayInputStream(selected.bytes()));
        DynamicTexture texture = new DynamicTexture(image);
        texture.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL,
                31 - Integer.numberOfLeadingZeros(Math.max(image.getWidth(), image.getHeight())));
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        textureManager.register(location, texture);
    }

    record ReloadData(Map<ResourceLocation, GltfModel> models, Map<ResourceLocation, String> failures) {
    }
}
