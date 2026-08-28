package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.GltfRendererApi;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

final class GltfShaderPackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INVOCATION_FAILURE_LOGGED = new AtomicBoolean();
    private static final Method GET_INSTANCE;
    private static final Method IS_SHADER_PACK_IN_USE;
    private static final Method IS_RENDERING_SHADOW_PASS;

    static {
        Method getInstance = null;
        Method isShaderPackInUse = null;
        Method isRenderingShadowPass = null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            getInstance = api.getMethod("getInstance");
            isShaderPackInUse = api.getMethod("isShaderPackInUse");
            isRenderingShadowPass = api.getMethod("isRenderingShadowPass");
            LOGGER.info("Oculus/Iris shader-pack integration detected");
        } catch (ClassNotFoundException exception) {
            if (ModList.get().isLoaded("oculus")) {
                LOGGER.error("Oculus is loaded but its shader API is unavailable. Support: {}",
                        GltfRendererApi.SUPPORT_URL, exception);
            } else {
                LOGGER.debug("Oculus/Iris shader-pack integration is not installed");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.error("Could not initialize Oculus/Iris shader-pack integration. Support: {}",
                    GltfRendererApi.SUPPORT_URL, exception);
        }
        GET_INSTANCE = getInstance;
        IS_SHADER_PACK_IN_USE = isShaderPackInUse;
        IS_RENDERING_SHADOW_PASS = isRenderingShadowPass;
    }

    private GltfShaderPackCompat() {
    }

    static boolean isShaderPackInUse() {
        return invoke(IS_SHADER_PACK_IN_USE);
    }

    static boolean isRenderingShadowPass() {
        return invoke(IS_RENDERING_SHADOW_PASS);
    }

    private static boolean invoke(Method method) {
        if (GET_INSTANCE == null || method == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(GET_INSTANCE.invoke(null)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (INVOCATION_FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Oculus/Iris shader-pack state query failed. Support: {}",
                        GltfRendererApi.SUPPORT_URL, exception);
            }
            return false;
        }
    }
}
