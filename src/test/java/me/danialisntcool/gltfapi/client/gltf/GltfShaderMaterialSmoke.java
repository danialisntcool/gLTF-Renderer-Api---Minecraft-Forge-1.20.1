package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

public final class GltfShaderMaterialSmoke {
    public static void verify() throws Exception {
        var constructor = GltfShaderMaterialTextures.MaterialTexture.class.getDeclaredConstructor(
                int.class, int.class, int[].class, int[].class, GltfSampler.class);
        constructor.setAccessible(true);
        var upload = GltfShaderMaterialTextures.MaterialTexture.class.getDeclaredMethod("upload", NativeImage.class);
        upload.setAccessible(true);
        var map = GltfShaderMaterialTextures.MaterialTexture.class.getDeclaredMethod("map", boolean.class);
        map.setAccessible(true);
        int[] normalPixels = new int[4];
        int[] specularPixels = new int[4];
        Arrays.fill(normalPixels, 0xffff8080);
        Arrays.fill(specularPixels, GltfShaderMaterialTextures.encodeSpecular(0.2F, 1, 0, true));
        AbstractTexture texture = (AbstractTexture) constructor.newInstance(2, 2, normalPixels, specularPixels, GltfSampler.DEFAULT);
        try (GltfNativeState ignored = new GltfNativeState();
             NativeImage image = new NativeImage(2, 2, false)) {
            image.fillRect(0, 0, 2, 2, -1);
            upload.invoke(texture, image);
            texture.setFilter(false, false);
            if (GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER) != GltfSampler.DEFAULT.minFilter()
                    || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 1, GL11.GL_TEXTURE_WIDTH) != 1)
                throw new IllegalStateException("Shader material lost its sampler or mipmaps during RenderType filtering");
            for (boolean isNormal : new boolean[]{true, false}) {
                AbstractTexture generated = (AbstractTexture) map.invoke(texture, isNormal);
                try {
                    generated.bind();
                    image.downloadTexture(0, false);
                    if (image.getPixelRGBA(0, 0) != (isNormal ? normalPixels[0] : specularPixels[0])
                            || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 1, GL11.GL_TEXTURE_WIDTH) != 1)
                        throw new IllegalStateException("Shader material map changed channel encoding or omitted mipmaps");
                } finally {
                    generated.close();
                    generated.releaseId();
                }
            }
            verifyOculus(texture, normalPixels[0], specularPixels[0]);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("Shader material upload GL error");
            System.out.println("Shader material GPU regression passed: normal/specular channel encoding, mipmaps and preserved glTF filtering");
        } finally {
            texture.releaseId();
        }
    }

    private static void verifyOculus(AbstractTexture texture, int normal, int specular) throws Exception {
        String path = System.getenv("GLTF_OCULUS_TEST_JAR");
        if (path == null || path.isBlank()) return;
        try (var classLoader = new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(path).toUri().toURL()},
                GltfShaderMaterialSmoke.class.getClassLoader())) {
            GltfShaderMaterialTextures.register(classLoader);
            Class<?> registry = classLoader.loadClass("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoaderRegistry");
            Class<?> loaderType = classLoader.loadClass("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader");
            Class<?> consumerType = classLoader.loadClass("net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader$PBRTextureConsumer");
            Object loader = registry.getMethod("getLoader", Class.class)
                    .invoke(registry.getField("INSTANCE").get(null), texture.getClass());
            AbstractTexture[] maps = new AbstractTexture[2];
            Object consumer = java.lang.reflect.Proxy.newProxyInstance(classLoader, new Class<?>[]{consumerType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("acceptNormalTexture")) maps[0] = (AbstractTexture) args[0];
                        if (method.getName().equals("acceptSpecularTexture")) maps[1] = (AbstractTexture) args[0];
                        return null;
                    });
            try {
                loaderType.getMethod("load", AbstractTexture.class, net.minecraft.server.packs.resources.ResourceManager.class,
                        consumerType).invoke(loader, texture, null, consumer);
                try (NativeImage image = new NativeImage(2, 2, false)) {
                    for (int index = 0; index < maps.length; index++) {
                        if (maps[index] == null) throw new IllegalStateException("Oculus did not receive the generated material map");
                        maps[index].bind();
                        image.downloadTexture(0, false);
                        if (image.getPixelRGBA(0, 0) != (index == 0 ? normal : specular))
                            throw new IllegalStateException("Oculus received the wrong generated material texture");
                    }
                }
                System.out.println("Oculus 1.8.0 material bridge regression passed: actual loader registry and both GPU maps delivered");
            } finally {
                for (AbstractTexture map : maps) if (map != null) { map.close(); map.releaseId(); }
            }
        }
    }
}
