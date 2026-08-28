package me.danialisntcool.gltfapi.client;

import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.GltfRendererApi;
import me.danialisntcool.gltfapi.client.gltf.GltfModelManager;
import me.danialisntcool.gltfapi.client.gltf.GltfGeometryLoader;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import me.danialisntcool.gltfapi.client.test.TestCharacterRenderer;
import me.danialisntcool.gltfapi.client.benchmark.GltfBenchmark;
import me.danialisntcool.gltfapi.test.TestContent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

public final class GltfRendererApiClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final GltfBenchmark BENCHMARK = new GltfBenchmark();
    private GltfRendererApiClient() {
    }

    public static void initialize(IEventBus modEventBus) {
        modEventBus.addListener(GltfRendererApiClient::registerReloadListeners);
        modEventBus.addListener(GltfRendererApiClient::registerShaders);
        modEventBus.addListener(GltfRendererApiClient::registerEntityRenderers);
        modEventBus.addListener(GltfRendererApiClient::registerGeometryLoaders);
        MinecraftForge.EVENT_BUS.register(BENCHMARK);
        LOGGER.info("glTF Renderer API client initialized. Support: {}", GltfRendererApi.SUPPORT_URL);
    }

    private static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register("gltf", GltfGeometryLoader.INSTANCE);
    }

    private static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(GltfModelManager.getInstance());
    }

    private static void registerShaders(RegisterShadersEvent event) {
        GltfShaders.register(event);
    }

    private static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TestContent.TEST_CHARACTER.get(), TestCharacterRenderer::new);
    }

}
