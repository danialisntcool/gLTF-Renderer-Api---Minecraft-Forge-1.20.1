package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.ktx.KTX;
import org.lwjgl.util.ktx.ktxTexture;
import org.lwjgl.util.ktx.ktxTexture2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

final class KtxTexture extends AbstractTexture {
    private final byte[] encoded;
    private boolean loaded;

    KtxTexture(byte[] encoded) {
        this.encoded = encoded.clone();
    }

    @Override
    public void load(ResourceManager resourceManager) throws IOException {
        RenderSystem.assertOnRenderThreadOrInit();
        if (loaded) {
            return;
        }
        ByteBuffer data = MemoryUtil.memAlloc(encoded.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data.put(encoded).flip();
            PointerBuffer output = stack.mallocPointer(1);
            check(KTX.ktxTexture2_CreateFromMemory(data, KTX.KTX_TEXTURE_CREATE_LOAD_IMAGE_DATA_BIT, output), "create");
            ktxTexture2 texture = ktxTexture2.create(output.get(0));
            try {
                if (KTX.ktxTexture2_NeedsTranscoding(texture)) {
                    check(KTX.ktxTexture2_TranscodeBasis(texture, selectFormat(), 0), "transcode");
                }
                IntBuffer textureName = stack.ints(0);
                IntBuffer target = stack.mallocInt(1);
                IntBuffer error = stack.mallocInt(1);
                check(KTX.ktxTexture_GLUpload(ktxTexture.create(texture.address()), textureName, target, error), "upload");
                releaseId();
                id = textureName.get(0);
                loaded = true;
            } finally {
                KTX.ktxTexture_Destroy(ktxTexture.create(texture.address()));
            }
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException("KTX2/BasisU texture decoding failed", exception);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private int selectFormat() {
        GLCapabilities capabilities = GL.getCapabilities();
        if (capabilities.GL_ARB_texture_compression_bptc) {
            return KTX.KTX_TTF_BC7_RGBA;
        }
        if (capabilities.GL_EXT_texture_compression_s3tc) {
            return KTX.KTX_TTF_BC3_RGBA;
        }
        return KTX.KTX_TTF_RGBA32;
    }

    private void check(int code, String operation) throws IOException {
        if (code != KTX.KTX_SUCCESS) {
            throw new IOException("KTX2 " + operation + " failed: " + KTX.ktxErrorString(code));
        }
    }
}
