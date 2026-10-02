package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;

final class GltfEnvironmentTexture extends AbstractTexture {
    static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath(
            "gltf_renderer_api", "textures/environment/reflection.png");

    @Override
    public void load(ResourceManager resources) throws IOException {
        RenderSystem.assertOnRenderThreadOrInit();
        NativeImage image;
        var resource = resources.getResource(LOCATION);
        if (resource.isPresent()) {
            try (InputStream input = resource.get().open()) {
                image = NativeImage.read(input);
            }
        } else {
            image = createPanorama();
        }
        try (image) {
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(image.getWidth(), image.getHeight()));
            TextureUtil.prepareImage(getId(), levels, image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, false);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
    }

    private static NativeImage createPanorama() {
        NativeImage image = new NativeImage(256, 128, false);
        for (int y = 0; y < 128; y++) {
            double elevation = Math.cos(Math.PI * (y + 0.5) / 128.0);
            double sky = Math.max(0.0, elevation);
            double horizon = Math.exp(-elevation * elevation * 18.0);
            for (int x = 0; x < 256; x++) {
                double azimuth = 2.0 * Math.PI * (x + 0.5) / 256.0;
                double window = Math.pow(Math.max(0.0, Math.cos(azimuth - 0.6)), 24.0)
                        * Math.exp(-Math.pow(elevation - 0.30, 2.0) * 35.0);
                double fill = Math.pow(Math.max(0.0, Math.cos(azimuth + 1.9)), 8.0)
                        * Math.exp(-Math.pow(elevation - 0.45, 2.0) * 10.0);
                double ground = elevation < 0.0 ? 0.15 + (elevation + 1.0) * 0.12 : 0.0;
                int red = channel(ground + sky * 0.40 + horizon * 0.45 + window * 0.65 + fill * 0.25);
                int green = channel(ground + sky * 0.53 + horizon * 0.48 + window * 0.60 + fill * 0.27);
                int blue = channel(ground + sky * 0.75 + horizon * 0.54 + window * 0.52 + fill * 0.32);
                image.setPixelRGBA(x, y, 0xff000000 | blue << 16 | green << 8 | red);
            }
        }
        return image;
    }

    private static int channel(double value) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
    }
}
