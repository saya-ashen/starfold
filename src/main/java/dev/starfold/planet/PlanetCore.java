package dev.starfold.planet;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The shell of a planet's core keeps the planet from being dug through. Once in a native server chunk it cannot
 *  be replaced, even by creative players or admin commands. Generation writes its own sections before live play. */
public final class PlanetCore {
    /** Faint enough to leave the deep dark, enough to see the floor one has dug down to. */
    public static final int LIGHT = 3;

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("starfold");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("starfold");
    public static Block SHELL_BLOCK;

    /** Retain the actual Block field; construct and bind it during its registration event. */
    public static void initialize(IEventBus modBus) {
        BLOCKS.register(
                "core_shell",
                () ->
                        SHELL_BLOCK =
                                new PlanetCoreBlock(
                                        BlockBehaviour.Properties.of()
                                                .mapColor(MapColor.COLOR_BLACK)
                                                .strength(-1, 3600000)
                                                .sound(SoundType.NETHERITE_BLOCK)
                                                .lightLevel(s -> LIGHT)
                                                .pushReaction(PushReaction.BLOCK)
                                                .isValidSpawn((s, l, p, e) -> false)
                                                .noLootTable()));
        ITEMS.register("core_shell", () -> new BlockItem(SHELL_BLOCK, new Item.Properties()));
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }

    public static BlockState shell() {
        return SHELL_BLOCK.defaultBlockState();
    }

    private PlanetCore() {}
}
