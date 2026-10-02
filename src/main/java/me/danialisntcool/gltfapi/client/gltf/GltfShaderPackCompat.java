package me.danialisntcool.gltfapi.client.gltf;

import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.GltfRendererApi;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;

final class GltfShaderPackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INVOCATION_FAILURE_LOGGED = new AtomicBoolean();
    private static final MethodHandle IS_SHADER_PACK_IN_USE;
    private static final MethodHandle IS_RENDERING_SHADOW_PASS;
    private static final MethodHandle[] ENTITY_STATE = new MethodHandle[6];

    static {
        MethodHandle isShaderPackInUse = null;
        MethodHandle isRenderingShadowPass = null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isShaderPackInUse = lookup.unreflect(api.getMethod("isShaderPackInUse")).bindTo(instance)
                    .asType(MethodType.methodType(boolean.class));
            isRenderingShadowPass = lookup.unreflect(api.getMethod("isRenderingShadowPass")).bindTo(instance)
                    .asType(MethodType.methodType(boolean.class));
            try {
                Class<?> state = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                Object captured = state.getField("INSTANCE").get(null);
                String[] getters = {"getCurrentRenderedEntity", "getCurrentRenderedBlockEntity", "getCurrentRenderedItem"};
                String[] setters = {"setCurrentEntity", "setCurrentBlockEntity", "setCurrentRenderedItem"};
                for (int index = 0; index < 3; index++) {
                    ENTITY_STATE[index] = lookup.unreflect(state.getMethod(getters[index])).bindTo(captured)
                            .asType(MethodType.methodType(int.class));
                    ENTITY_STATE[index + 3] = lookup.unreflect(state.getMethod(setters[index], int.class)).bindTo(captured)
                            .asType(MethodType.methodType(void.class, int.class));
                }
            } catch (ReflectiveOperationException | LinkageError exception) {
                java.util.Arrays.fill(ENTITY_STATE, null);
            }
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

    static EntityState captureEntityState() {
        if (!isShaderPackInUse()) return EntityState.DEFAULT;
        if (ENTITY_STATE[0] == null) return null;
        try {
            return new EntityState((int) ENTITY_STATE[0].invokeExact(),
                    (int) ENTITY_STATE[1].invokeExact(), (int) ENTITY_STATE[2].invokeExact());
        } catch (Throwable exception) {
            if (exception instanceof VirtualMachineError fatal) throw fatal;
            if (exception instanceof ThreadDeath fatal) throw fatal;
            throw new IllegalStateException("Could not capture Oculus entity state", exception);
        }
    }

    static void applyEntityState(EntityState state) {
        if (ENTITY_STATE[0] == null || !isShaderPackInUse()) return;
        try {
            ENTITY_STATE[3].invokeExact(state.entity);
            ENTITY_STATE[4].invokeExact(state.blockEntity);
            ENTITY_STATE[5].invokeExact(state.item);
        } catch (Throwable exception) {
            if (exception instanceof VirtualMachineError fatal) throw fatal;
            if (exception instanceof ThreadDeath fatal) throw fatal;
            throw new IllegalStateException("Could not restore Oculus entity state", exception);
        }
    }

    record EntityState(int entity, int blockEntity, int item) {
        private static final EntityState DEFAULT = new EntityState(0, 0, 0);
    }

    private static boolean invoke(MethodHandle method) {
        if (method == null) {
            return false;
        }
        try {
            return (boolean) method.invokeExact();
        } catch (Throwable exception) {
            if (exception instanceof VirtualMachineError fatal) throw fatal;
            if (exception instanceof ThreadDeath fatal) throw fatal;
            if (INVOCATION_FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("Oculus/Iris shader-pack state query failed. Support: {}",
                        GltfRendererApi.SUPPORT_URL, exception);
            }
            return false;
        }
    }
}
