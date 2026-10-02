package me.danialisntcool.gltfapi;

import com.mojang.logging.LogUtils;
import me.danialisntcool.gltfapi.test.TestCharacter;
import me.danialisntcool.gltfapi.test.TestContent;
import me.danialisntcool.gltfapi.generated.ModMetadata;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.CrashReportCallables;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(GltfRendererApi.MOD_ID)
public final class GltfRendererApi {
    public static final String MOD_ID = ModMetadata.MOD_ID;
    public static final String SUPPORT_URL = ModMetadata.SUPPORT_URL;
    private static final Logger LOGGER = LogUtils.getLogger();

    public GltfRendererApi(FMLJavaModLoadingContext context) {
        CrashReportCallables.registerCrashCallable("glTF Renderer API Support", () -> SUPPORT_URL);
        LOGGER.info("Initializing {} {}. Support: {}", ModMetadata.MOD_NAME, ModMetadata.MOD_VERSION, SUPPORT_URL);
        IEventBus modEventBus = context.getModEventBus();
        TestContent.register(modEventBus);
        modEventBus.addListener(this::createEntityAttributes);
        modEventBus.addListener(this::buildCreativeTab);
    }

    private void createEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(TestContent.TEST_CHARACTER.get(), TestCharacter.createAttributes().build());
    }

    private void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            TestContent.TEST_CHARACTER_SPAWN_EGG.ifPresent(event::accept);
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            TestContent.TEST_STATIC_BLOCK_ITEM.ifPresent(event::accept);
        }
    }
}
