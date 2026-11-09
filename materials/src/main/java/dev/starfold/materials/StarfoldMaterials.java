package dev.starfold.materials;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Hull components and aluminum stock; ore placement belongs to the planet datapack. */
@Mod("starfold_materials")
public final class StarfoldMaterials {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("starfold_materials");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("starfold_materials");

    public StarfoldMaterials(IEventBus bus) {
        // Provisional hardness/resistance; mass and pressure are datapack-defined.
        for (String name : new String[] {"aluminum_hull_plating", "osmium_pressure_hull"}) {
            var full = BLOCKS.register(name, () -> new Block(properties()));
            var slab = BLOCKS.register(name + "_slab", () -> new SlabBlock(properties()));
            ITEMS.registerSimpleBlockItem(full);
            ITEMS.registerSimpleBlockItem(slab);
        }
        for (String name : new String[] {"bauxite_ore", "deepslate_bauxite_ore", "raw_aluminum_block", "aluminum_block"}) {
            Block template = switch (name) {
                case "bauxite_ore" -> Blocks.IRON_ORE;
                case "deepslate_bauxite_ore" -> Blocks.DEEPSLATE_IRON_ORE;
                case "raw_aluminum_block" -> Blocks.RAW_IRON_BLOCK;
                default -> Blocks.IRON_BLOCK;
            };
            var block = BLOCKS.register(name, () -> new Block(Block.Properties.ofFullCopy(template)));
            ITEMS.registerSimpleBlockItem(block);
        }
        for (String name : new String[] {"raw_aluminum", "aluminum_ingot", "aluminum_nugget"})
            ITEMS.registerSimpleItem(name);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey().equals(CreativeModeTabs.BUILDING_BLOCKS))
                ITEMS.getEntries().forEach(item -> e.accept(item.get()));
        });
    }

    private static Block.Properties properties() {
        return Block.Properties.of().mapColor(MapColor.METAL).sound(SoundType.METAL)
                .strength(3, 6).requiresCorrectToolForDrops();
    }
}
