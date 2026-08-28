package me.danialisntcool.gltfapi.test;

import me.danialisntcool.gltfapi.GltfRendererApi;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class TestContent {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, GltfRendererApi.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, GltfRendererApi.MOD_ID);
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, GltfRendererApi.MOD_ID);
    public static final RegistryObject<EntityType<TestCharacter>> TEST_CHARACTER = ENTITY_TYPES.register(
            "test_character",
            () -> EntityType.Builder.of(TestCharacter::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build(GltfRendererApi.MOD_ID + ":test_character"));
    public static final RegistryObject<Item> TEST_CHARACTER_SPAWN_EGG = ITEMS.register(
            "test_character_spawn_egg",
            () -> new ForgeSpawnEggItem(TEST_CHARACTER, 0x55AEDD, 0x17243A, new Item.Properties()));
    public static final RegistryObject<Block> TEST_STATIC_BLOCK = BLOCKS.register(
            "test_static_block",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.5F)));
    public static final RegistryObject<Item> TEST_STATIC_BLOCK_ITEM = ITEMS.register(
            "test_static_block",
            () -> new BlockItem(TEST_STATIC_BLOCK.get(), new Item.Properties()));

    private TestContent() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
