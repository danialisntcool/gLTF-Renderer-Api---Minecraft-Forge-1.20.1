package me.danialisntcool.gltfapi.client;

import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.GltfRendererApi;
import me.danialisntcool.gltfapi.client.gltf.GltfModelManager;
import me.danialisntcool.gltfapi.client.gltf.GltfGeometryLoader;
import me.danialisntcool.gltfapi.client.render.GltfShaders;
import me.danialisntcool.gltfapi.client.test.TestCharacterRenderer;
import me.danialisntcool.gltfapi.client.benchmark.GltfBenchmark;
import me.danialisntcool.gltfapi.api.client.render.GltfItemModels;
import me.danialisntcool.gltfapi.test.TestContent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = GltfRendererApi.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GltfRendererApiClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final GltfBenchmark BENCHMARK = new GltfBenchmark();
    private GltfRendererApiClient() {
    }

    @SubscribeEvent
    public static void registerConfig(FMLConstructModEvent event) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, GltfClientConfig.SPEC);
    }

    @SubscribeEvent
    public static void initialize(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(BENCHMARK);
        MinecraftForge.EVENT_BUS.register(new me.danialisntcool.gltfapi.client.gltf.GltfWorldQueue());
        MinecraftForge.EVENT_BUS.register(new me.danialisntcool.gltfapi.client.benchmark.GltfSceneProfiler());
        LOGGER.info("glTF Renderer API client initialized. Support: {}", GltfRendererApi.SUPPORT_URL);
    }

    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register("gltf", GltfGeometryLoader.INSTANCE);
    }

    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        GltfItemModels.registerAdditional(event);
    }

    @SubscribeEvent
    public static void replaceItemModels(ModelEvent.ModifyBakingResult event) {
        GltfItemModels.apply(event);
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(GltfModelManager.getInstance());
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) {
        GltfShaders.register(event);
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TestContent.TEST_CHARACTER.get(), TestCharacterRenderer::new);
    }

}
