package dev.starfold.mining;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(StarfoldMining.MOD_ID)
public final class StarfoldMining {
    public static final String MOD_ID = "starfold_mining";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final DeferredBlock<MiningPlatformBlock> PLATFORM =
            BLOCKS.register(
                    "mining_platform",
                    () ->
                            new MiningPlatformBlock(
                                    BlockBehaviour.Properties.of()
                                            .strength(3.5F)
                                            .requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> PLATFORM_ITEM =
            ITEMS.registerSimpleBlockItem(PLATFORM);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MiningBlockEntity>>
            PLATFORM_BE =
                    ENTITIES.register(
                            "mining_platform",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    MiningBlockEntity::new, PLATFORM.get())
                                            .build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<MiningMenu>> MENU =
            MENUS.register("mining_platform", () -> IMenuTypeExtension.create(MiningMenu::new));

    public static final DeferredBlock<OilExtractorBlock> OIL =
            BLOCKS.register(
                    "oil_extractor",
                    () ->
                            new OilExtractorBlock(
                                    BlockBehaviour.Properties.of()
                                            .strength(3.5F)
                                            .requiresCorrectToolForDrops()
                                            .pushReaction(
                                                    net.minecraft.world.level.material.PushReaction
                                                            .BLOCK)));
    public static final DeferredItem<BlockItem> OIL_ITEM = ITEMS.registerSimpleBlockItem(OIL);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OilExtractorBlockEntity>>
            OIL_BE =
                    ENTITIES.register(
                            "oil_extractor",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    OilExtractorBlockEntity::new, OIL.get())
                                            .build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<OilMenu>> OIL_MENU =
            MENUS.register("oil_extractor", () -> IMenuTypeExtension.create(OilMenu::new));

    public StarfoldMining(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener(StarfoldMining::capabilities);
        bus.addListener(
                (net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
                    if (event.getTabKey()
                            == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS)
                        event.accept(OIL_ITEM);
                });
        MiningSurveys.initialize(bus);
        MiningBlockEntity.SCAN_COMPLETED =
                be ->
                        MiningSurveys.completed(
                                (net.minecraft.server.level.ServerLevel) be.getLevel(),
                                be.getBlockPos(),
                                be.owner(),
                                be.summary().stream()
                                        .map(
                                                ore ->
                                                        new dev.starfold.api.MiningMapApi.Ore(
                                                                ore.id(),
                                                                ore.count(),
                                                                ore.minDepth(),
                                                                ore.maxDepth()))
                                        .toList(),
                                MiningBlockEntity.DEPTH,
                                MiningBlockEntity.TOTAL);
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK, OIL_BE.get(), (be, side) -> be.automation());
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK, OIL_BE.get(), (be, side) -> be.energyStorage());
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK, OIL_BE.get(), (be, side) -> be.fluidOutlet());
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK, PLATFORM_BE.get(), (be, side) -> be.automation());
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                PLATFORM_BE.get(),
                (be, side) -> be.energyStorage());
    }
}
