package me.danialisntcool.gltfapi.client.gltf;

import me.danialisntcool.gltfapi.GltfRendererApi;

public final class GltfLoadException extends RuntimeException {
    public GltfLoadException(String message) {
        super(withSupport(message));
    }

    public GltfLoadException(String message, Throwable cause) {
        super(withSupport(message), cause);
    }

    private static String withSupport(String message) {
        return message.contains(GltfRendererApi.SUPPORT_URL)
                ? message
                : message + " | Support: " + GltfRendererApi.SUPPORT_URL;
    }
}
