package me.danialisntcool.gltfapi.client.render;

import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.GltfRendererApi;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

import java.io.IOException;

public final class GltfShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ShaderInstance modelShader;
    private static ShaderInstance bufferedModelShader;
    private static ShaderInstance iconShader;

    private GltfShaders() {
    }

    public static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(GltfRendererApi.MOD_ID, "gltf_icon"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX), shader -> iconShader = shader);
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(GltfRendererApi.MOD_ID, "gltf_model"),
                            GltfVertexFormats.MORPHED_MODEL),
                    shader -> {
                        modelShader = shader;
                        LOGGER.info("Registered the glTF model shader");
                    });
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(GltfRendererApi.MOD_ID, "gltf_buffered_model"),
                            GltfVertexFormats.BUFFERED_PBR),
                    shader -> bufferedModelShader = shader);
        } catch (IOException exception) {
            String message = "Could not load the glTF model shader | Support: " + GltfRendererApi.SUPPORT_URL;
            LOGGER.error(message, exception);
            throw new IllegalStateException(message, exception);
        }
    }

    public static ShaderInstance modelShader() {
        if (modelShader == null) {
            throw new IllegalStateException("The glTF model shader is not loaded | Support: "
                    + GltfRendererApi.SUPPORT_URL);
        }
        return modelShader;
    }

    public static ShaderInstance bufferedModelShader() {
        if (bufferedModelShader == null) {
            throw new IllegalStateException("The glTF buffered material shader is not loaded | Support: "
                    + GltfRendererApi.SUPPORT_URL);
        }
        return bufferedModelShader;
    }

    public static ShaderInstance iconShader() {
        if (iconShader == null) throw new IllegalStateException("The glTF icon shader is not loaded");
        return iconShader;
    }
}
