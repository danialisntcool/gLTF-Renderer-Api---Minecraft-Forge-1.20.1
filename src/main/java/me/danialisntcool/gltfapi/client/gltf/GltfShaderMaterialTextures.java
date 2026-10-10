package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.client.GltfClientConfig;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

final class GltfShaderMaterialTextures {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Key, ResourceLocation> TEXTURES = new HashMap<>();
    private static boolean initialized;
    private static boolean available;
    private static boolean failureLogged;
    private static long bytes;
    private static int sequence;

    private GltfShaderMaterialTextures() {
    }

    static ResourceLocation texture(GltfMaterial material, ResourceLocation fallback) {
        var format = GltfClientConfig.SHADER_MATERIAL_FORMAT.get();
        if (format == GltfClientConfig.ShaderMaterialFormat.DISABLED || !initialize()) return fallback;
        Key key = new Key(material, format);
        return TEXTURES.computeIfAbsent(key, ignored -> create(material, format, fallback));
    }

    private static boolean initialize() {
        if (initialized) return available;
        initialized = true;
        try {
            register(GltfShaderMaterialTextures.class.getClassLoader());
            available = true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            warn("Oculus material bridge unavailable; retaining base-color rendering", exception);
        }
        return available;
    }

    static void register(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> registryClass = Class.forName("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoaderRegistry", true, classLoader);
        Class<?> loaderClass = Class.forName("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader", true, classLoader);
        Class<?> consumerClass = Class.forName("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader$PBRTextureConsumer", true, classLoader);
        var normal = consumerClass.getMethod("acceptNormalTexture", AbstractTexture.class);
        var specular = consumerClass.getMethod("acceptSpecularTexture", AbstractTexture.class);
        Object loader = Proxy.newProxyInstance(loaderClass.getClassLoader(), new Class<?>[]{loaderClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("load")) {
                        MaterialTexture texture = (MaterialTexture) args[0];
                        AbstractTexture normalMap = texture.map(true);
                        AbstractTexture specularMap = null;
                        try {
                            specularMap = texture.map(false);
                            normal.invoke(args[2], normalMap);
                            specular.invoke(args[2], specularMap);
                        } catch (Throwable exception) {
                            normalMap.close();
                            normalMap.releaseId();
                            if (specularMap != null) { specularMap.close(); specularMap.releaseId(); }
                            throw exception;
                        }
                        return null;
                    }
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "glTF material texture loader";
                        default -> null;
                    };
                });
        registryClass.getMethod("register", Class.class, loaderClass)
                .invoke(registryClass.getField("INSTANCE").get(null), MaterialTexture.class, loader);
    }

    private static ResourceLocation create(GltfMaterial material, GltfClientConfig.ShaderMaterialFormat format,
                                           ResourceLocation fallback) {
        try (GltfNativeState ignored = new GltfNativeState();
             NativeImage base = read(material.baseColorTexture());
             NativeImage metallic = read(material.metallicRoughnessTexture());
             NativeImage normal = read(material.normalTexture());
             NativeImage occlusion = read(material.occlusionTexture());
             NativeImage emissive = read(material.emissiveTexture())) {
            int width = 16;
            int height = 16;
            for (NativeImage image : new NativeImage[]{base, metallic, normal, occlusion, emissive}) {
                if (image != null) {
                    width = Math.max(width, image.getWidth());
                    height = Math.max(height, image.getHeight());
                }
            }
            long required = (long) width * height * 28;
            if (required > ModMetadata.GPU_STREAM_CACHE_BYTES || bytes + required > ModMetadata.GPU_STREAM_CACHE_BYTES) {
                warn("Shader material texture budget reached; retaining base-color rendering", null);
                return fallback;
            }
            int[] normalPixels = new int[width * height];
            int[] specularPixels = new int[width * height];
            try (NativeImage color = new NativeImage(width, height, false)) {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    float u = (x + 0.5F) / width;
                    float v = (y + 0.5F) / height;
                    int basePixel = base == null ? -1 : base.getPixelRGBA(
                            Math.min(base.getWidth() - 1, (int) (u * base.getWidth())),
                            Math.min(base.getHeight() - 1, (int) (v * base.getHeight())));
                    int mr = sample(metallic, material.metallicRoughnessTexture(), material.baseColorTexture(), u, v, -1);
                    int n = sample(normal, material.normalTexture(), material.baseColorTexture(), u, v, 0xffff8080);
                    int ao = sample(occlusion, material.occlusionTexture(), material.baseColorTexture(), u, v, -1);
                    int e = sample(emissive, material.emissiveTexture(), material.baseColorTexture(), u, v, -1);
                    float roughness = clamp(material.roughnessFactor() * channel(mr, 8));
                    float metal = clamp(material.metallicFactor() * channel(mr, 16));
                    float emission = Math.max(material.emissiveRed() * channel(e, 0),
                            Math.max(material.emissiveGreen() * channel(e, 8), material.emissiveBlue() * channel(e, 16)));
                    float nx = (channel(n, 0) * 2 - 1) * material.normalScale();
                    float ny = (channel(n, 8) * 2 - 1) * material.normalScale();
                    float nz = channel(n, 16) * 2 - 1;
                    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (length > 0) { nx /= length; ny /= length; nz /= length; }
                    int offset = y * width + x;
                    boolean lab = format == GltfClientConfig.ShaderMaterialFormat.LAB_PBR;
                    float ambient = 1 - material.occlusionStrength() * (1 - channel(ao, 0));
                    normalPixels[offset] = pack(nx * 0.5F + 0.5F, lab ? 0.5F - ny * 0.5F : ny * 0.5F + 0.5F,
                            lab ? ambient : nz * 0.5F + 0.5F, 1);
                    specularPixels[offset] = encodeSpecular(roughness, metal, emission, lab);
                    if (material.alphaMode() == GltfMaterial.AlphaMode.MASK
                            && channel(basePixel, 24) * material.alpha() < material.alphaCutoff()) basePixel &= 0x00ffffff;
                    color.setPixelRGBA(x, y, basePixel);
                }
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath("gltf_renderer_api",
                        "generated/shader_material_" + sequence++);
                MaterialTexture texture = new MaterialTexture(width, height, normalPixels, specularPixels,
                        material.baseColorTexture() == null ? GltfSampler.DEFAULT : material.baseColorTexture().sampler());
                try {
                    texture.upload(color);
                    Minecraft.getInstance().getTextureManager().register(location, texture);
                } catch (RuntimeException | LinkageError exception) {
                    texture.releaseId();
                    throw exception;
                }
                bytes += required;
                return location;
            }
        } catch (RuntimeException | LinkageError exception) {
            warn("Could not build shader material maps; retaining base-color rendering", exception);
            return fallback;
        }
    }

    static int encodeSpecular(float roughness, float metal, float emission, boolean lab) {
        int smoothness = Math.round(clamp(1 - roughness) * 255);
        if (!lab) return smoothness | Math.round(clamp(metal) * 255) << 8
                | Math.round(clamp(emission) * 255) << 16 | 0xff000000;
        int reflectance = metal >= 0.5F ? 255 : Math.round((0.04F + metal * 0.86F) * 255);
        return smoothness | reflectance << 8 | Math.round(clamp(emission) * 254) << 24;
    }

    private static NativeImage read(GltfTextureInfo info) {
        if (info == null) return null;
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(info.texture());
        texture.bind();
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0 || (long) width * height * 4
                > Math.min(ModMetadata.MAX_RESOURCE_BYTES, ModMetadata.STAGING_BYTES))
            throw new IllegalArgumentException("Invalid shader material texture dimensions");
        NativeImage image = new NativeImage(width, height, false);
        try { image.downloadTexture(0, false); return image; }
        catch (RuntimeException | LinkageError exception) { image.close(); throw exception; }
    }

    private static int sample(NativeImage image, GltfTextureInfo info, GltfTextureInfo base,
                              float u, float v, int fallback) {
        if (image == null || info == null) return fallback;
        if (info.textureCoordinate() != (base == null ? 0 : base.textureCoordinate())) {
            warn("Shader material map uses a different UV set; retaining its material factor without the map", null);
            return fallback;
        }
        if (base != null) {
            float x = u - base.offsetX();
            float y = v - base.offsetY();
            float sine = (float) Math.sin(base.rotation());
            float cosine = (float) Math.cos(base.rotation());
            if (Math.abs(base.scaleX()) < 0.000001F || Math.abs(base.scaleY()) < 0.000001F) return fallback;
            u = (cosine * x + sine * y) / base.scaleX();
            v = (-sine * x + cosine * y) / base.scaleY();
        }
        float sine = (float) Math.sin(info.rotation());
        float cosine = (float) Math.cos(info.rotation());
        float x = u * info.scaleX();
        float y = v * info.scaleY();
        u = wrap(info.offsetX() + cosine * x - sine * y, info.sampler().wrapS());
        v = wrap(info.offsetY() + sine * x + cosine * y, info.sampler().wrapT());
        return image.getPixelRGBA(Math.min(image.getWidth() - 1, (int) (u * image.getWidth())),
                Math.min(image.getHeight() - 1, (int) (v * image.getHeight())));
    }

    static float wrap(float value, int mode) {
        if (mode == GL12.GL_CLAMP_TO_EDGE) return clamp(value);
        if (mode == org.lwjgl.opengl.GL14.GL_MIRRORED_REPEAT) {
            float repeat = value - (float) Math.floor(value / 2) * 2;
            return repeat <= 1 ? repeat : 2 - repeat;
        }
        return value - (float) Math.floor(value);
    }

    private static float channel(int pixel, int shift) { return (pixel >>> shift & 255) / 255.0F; }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static int pack(float r, float g, float b, float a) {
        return Math.round(clamp(r) * 255) | Math.round(clamp(g) * 255) << 8
                | Math.round(clamp(b) * 255) << 16 | Math.round(clamp(a) * 255) << 24;
    }

    private static void warn(String message, Throwable exception) {
        if (!failureLogged) { failureLogged = true; LOGGER.warn(message + " | Support: " + ModMetadata.SUPPORT_URL, exception); }
    }

    static void clear() {
        for (ResourceLocation location : TEXTURES.values())
            if (location.getNamespace().equals("gltf_renderer_api") && location.getPath().startsWith("generated/shader_material_"))
                Minecraft.getInstance().getTextureManager().release(location);
        TEXTURES.clear();
        bytes = 0;
        failureLogged = false;
    }

    private record Key(GltfMaterial material, GltfClientConfig.ShaderMaterialFormat format) {
    }

    public static final class MaterialTexture extends AbstractTexture {
        private final int width;
        private final int height;
        private final int[] normal;
        private final int[] specular;
        private final GltfSampler sampler;

        private MaterialTexture(int width, int height, int[] normal, int[] specular, GltfSampler sampler) {
            this.width = width;
            this.height = height;
            this.normal = normal;
            this.specular = specular;
            this.sampler = sampler;
        }

        @Override
        public void load(ResourceManager resources) {
        }

        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            super.setFilter(blur, mipmap);
            filtering();
        }

        private void upload(NativeImage image) {
            RenderSystem.assertOnRenderThread();
            int levels = 31 - Integer.numberOfLeadingZeros(Math.max(width, height));
            TextureUtil.prepareImage(getId(), levels, width, height);
            image.upload(0, 0, 0, false);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            filtering();
        }

        private AbstractTexture map(boolean isNormal) {
            NativeImage image = new NativeImage(width, height, false);
            int[] pixels = isNormal ? normal : specular;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setPixelRGBA(x, y, pixels[y * width + x]);
            DynamicTexture texture = null;
            try {
                texture = new DynamicTexture(image);
                texture.bind();
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL,
                        31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                filtering();
                return texture;
            } catch (RuntimeException | LinkageError exception) {
                if (texture != null) texture.close(); else image.close();
                throw exception;
            }
        }

        private void filtering() {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, sampler.minFilter());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, sampler.magFilter());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, sampler.wrapS());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, sampler.wrapT());
        }
    }
}
