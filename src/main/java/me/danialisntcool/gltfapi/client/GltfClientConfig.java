package me.danialisntcool.gltfapi.client;

import me.danialisntcool.gltfapi.api.client.GltfRenderMode;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class GltfClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<GltfRenderMode> RENDER_MODE;
    public static final ForgeConfigSpec.BooleanValue ICON_CACHE;
    public static final ForgeConfigSpec.BooleanValue WORLD_BATCHING;
    public static final ForgeConfigSpec.DoubleValue MODEL_RENDER_DISTANCE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        RENDER_MODE = builder.defineEnum("renderMode", GltfRenderMode.AUTO);
        ICON_CACHE = builder.define("inventoryIconCache", true);
        WORLD_BATCHING = builder.define("worldBatching", true);
        MODEL_RENDER_DISTANCE = builder.defineInRange("modelRenderDistance", 0.0, 0.0, 4096.0);
        SPEC = builder.build();
    }

    private GltfClientConfig() {
    }
}
