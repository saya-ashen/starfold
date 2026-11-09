package dev.starfold.biotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Organic terrain stock and the first Flesh encounter. */
@Mod("starfold_biotech")
public final class StarfoldBiotech {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("starfold_biotech");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("starfold_biotech");

    private static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, "starfold_biotech");
    private static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, "starfold_biotech");
    public static final DeferredHolder<FluidType, FluidType> BLOOD_TYPE = FLUID_TYPES.register("blood", () -> new FluidType(
            FluidType.Properties.create().descriptionId("block.starfold_biotech.blood")
                    .canSwim(true).canPushEntity(true).canExtinguish(true).canConvertToSource(false)
                    .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                    .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY)));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> BLOOD = FLUIDS.register("blood",
            () -> new BaseFlowingFluid.Source(bloodProperties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_BLOOD = FLUIDS.register("flowing_blood",
            () -> new BaseFlowingFluid.Flowing(bloodProperties()));
    public static final DeferredHolder<Block, LiquidBlock> BLOOD_BLOCK = BLOCKS.register("blood",
            () -> new LiquidBlock(BLOOD.get(), Block.Properties.ofFullCopy(Blocks.WATER).mapColor(MapColor.COLOR_RED)));
    public static final DeferredHolder<Item, BucketItem> BLOOD_BUCKET = ITEMS.register("blood_bucket",
            () -> new BucketItem(BLOOD.get(), new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    private static BaseFlowingFluid.Properties bloodProperties() {
        return new BaseFlowingFluid.Properties(BLOOD_TYPE, BLOOD, FLOWING_BLOOD)
                .block(BLOOD_BLOCK).bucket(BLOOD_BUCKET).tickRate(10).slopeFindDistance(3).explosionResistance(100);
    }

    public StarfoldBiotech(IEventBus bus) {
        dev.starfold.biotech.terrain.HiveLandform.register();
        var chitin = BLOCKS.register("chitin", () -> new Block(Block.Properties.of()
                .mapColor(MapColor.TERRACOTTA_BROWN).sound(SoundType.BONE_BLOCK)
                .strength(2.5f, 5).requiresCorrectToolForDrops()));
        var flesh = BLOCKS.register("hive_flesh", () -> new Block(Block.Properties.ofFullCopy(Blocks.NETHER_WART_BLOCK)
                .mapColor(MapColor.COLOR_RED).strength(1.2f).sound(SoundType.SLIME_BLOCK)));
        var resin = BLOCKS.register("resin", () -> new HalfTransparentBlock(Block.Properties.ofFullCopy(Blocks.GLASS)
                .mapColor(MapColor.COLOR_ORANGE).strength(1.5f).noOcclusion()));
        var sac = BLOCKS.register("pulse_sac", () -> new Block(Block.Properties.ofFullCopy(Blocks.SHROOMLIGHT)
                .mapColor(MapColor.COLOR_RED).strength(.8f).lightLevel(s -> 9)));
        ITEMS.registerSimpleBlockItem(chitin);
        ITEMS.registerSimpleBlockItem(flesh);
        ITEMS.registerSimpleBlockItem(resin);
        ITEMS.registerSimpleBlockItem(sac);
        ITEMS.registerSimpleItem("chitin_plate");
        ITEMS.registerSimpleItem("resin_lump");
        ITEMS.registerSimpleItem("host_ganglion");
        dev.starfold.biotech.host.HostEntities.register(bus);
        dev.starfold.biotech.host.HostSounds.register(bus);
        ITEMS.register("host_spawn_egg", () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                dev.starfold.biotech.host.HostEntities.HOST, 0x57242e, 0xc8aa81, new net.minecraft.world.item.Item.Properties()));
        ITEMS.register("host_sac_spawn_egg", () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                dev.starfold.biotech.host.HostEntities.SAC, 0x722a3b, 0xad715d, new net.minecraft.world.item.Item.Properties()));
        ITEMS.register("host_drone_spawn_egg", () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(
                dev.starfold.biotech.host.HostEntities.DRONE, 0x5c3434, 0x8a8e71, new net.minecraft.world.item.Item.Properties()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(dev.starfold.biotech.host.HostEncounter::tick);
        FLUID_TYPES.register(bus);
        FLUIDS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey().equals(CreativeModeTabs.NATURAL_BLOCKS))
                ITEMS.getEntries().stream().filter(item -> !item.getId().getPath().endsWith("_spawn_egg"))
                        .forEach(item -> e.accept(item.get()));
            if (e.getTabKey().equals(CreativeModeTabs.SPAWN_EGGS))
                ITEMS.getEntries().stream().filter(item -> item.getId().getPath().endsWith("_spawn_egg"))
                        .forEach(item -> e.accept(item.get()));
        });
    }
}
