package me.danialisntcool.gltfapi;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.ktx.KTX;
import org.lwjgl.util.ktx.ktxTexture;
import org.lwjgl.util.ktx.ktxTexture2;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class KtxDecoderSmoke {
    private KtxDecoderSmoke() {
    }

    public static void main(String[] arguments) throws Exception {
        byte[] encoded = Files.readAllBytes(Path.of(arguments[0]));
        ByteBuffer data = MemoryUtil.memAlloc(encoded.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data.put(encoded).flip();
            PointerBuffer output = stack.mallocPointer(1);
            requireSuccess(KTX.ktxTexture2_CreateFromMemory(
                    data, KTX.KTX_TEXTURE_CREATE_LOAD_IMAGE_DATA_BIT, output));
            ktxTexture2 texture = ktxTexture2.create(output.get(0));
            try {
                if (!KTX.ktxTexture2_NeedsTranscoding(texture)) {
                    throw new IllegalStateException("Fixture is not BasisU compressed");
                }
                requireSuccess(KTX.ktxTexture2_TranscodeBasis(texture, KTX.KTX_TTF_RGBA32, 0));
                if (texture.baseWidth() <= 0 || texture.baseHeight() <= 0 || texture.dataSize() <= 0) {
                    throw new IllegalStateException("Decoded KTX2 texture is empty");
                }
                System.out.println(texture.baseWidth() + "x" + texture.baseHeight() + " " + texture.dataSize());
            } finally {
                KTX.ktxTexture_Destroy(ktxTexture.create(texture.address()));
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void requireSuccess(int result) {
        if (result != KTX.KTX_SUCCESS) {
            throw new IllegalStateException(KTX.ktxErrorString(result));
        }
    }
}
