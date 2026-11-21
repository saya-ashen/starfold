package dev.starfold.mining;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.List;

public final class MiningMenu extends AbstractContainerMenu {
    public static final int SCAN = 0, START_PAUSE = 1, RESET = 2, TOGGLE_OBSTRUCTIONS = 3;
    private static final int DATA_COUNT = 12;
    public final MiningBlockEntity platform;
    private final ContainerData data;

    public MiningMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, clientPlatform(inventory, buffer));
    }

    private static MiningBlockEntity clientPlatform(Inventory inventory, FriendlyByteBuf buffer) {
        var pos = buffer.readBlockPos();
        var blockEntity = inventory.player.level().getBlockEntity(pos);
        // Chunk/frame updates may still be in flight. Slot contents arrive through menu sync;
        // a temporary client-side holder prevents the screen from crashing during that gap.
        return blockEntity instanceof MiningBlockEntity be
                ? be
                : new MiningBlockEntity(pos, StarfoldMining.PLATFORM.get().defaultBlockState());
    }

    public MiningMenu(int id, Inventory inventory, MiningBlockEntity platform) {
        super(StarfoldMining.MENU.get(), id);
        this.platform = platform;
        data =
                inventory.player.level().isClientSide
                        ? new SimpleContainerData(DATA_COUNT)
                        : new ContainerData() {
                            @Override
                            public int get(int index) {
                                return platform.datum(index);
                            }

                            @Override
                            public void set(int index, int value) {}

                            @Override
                            public int getCount() {
                                return DATA_COUNT;
                            }
                        };
        addDataSlots(data);
        addSlot(new SlotItemHandler(platform.inventory, 0, 25, 37));
        for (int row = 0; row < 2; row++)
            for (int col = 0; col < 9; col++)
                addSlot(
                        new SlotItemHandler(
                                platform.inventory,
                                1 + row * 9 + col,
                                8 + col * 18,
                                99 + row * 18) {
                            @Override
                            public boolean mayPlace(ItemStack stack) {
                                return false;
                            }
                        });
        MachineMenu.addPlayerSlots(inventory, this::addSlot);
    }

    public int energy() {
        return data.get(0);
    }

    public int maxEnergy() {
        return data.get(1);
    }

    public int cursor() {
        return data.get(2);
    }

    public int scanProgress() {
        return data.get(3);
    }

    public int status() {
        return data.get(4);
    }

    public boolean enabled() {
        return data.get(5) != 0;
    }

    public boolean scanned() {
        return data.get(6) != 0;
    }

    public int total() {
        return data.get(7);
    }

    MiningBlockEntity.Obstruction obstruction() {
        return MiningBlockEntity.Obstruction.byId(data.get(8));
    }

    Block obstructingBlock() {
        int id = (data.get(9) & 0xffff) | (data.get(10) & 0xffff) << 16;
        return id < 0 ? null : BuiltInRegistries.BLOCK.byId(id);
    }

    int obstructionCursor() {
        if (obstruction() == MiningBlockEntity.Obstruction.SCAN_TOO_DEEP) return total() - 1;
        return scanned() ? cursor() : scanProgress();
    }

    boolean skipsObstructions() {
        return data.get(11) != 0;
    }

    public List<MiningBlockEntity.OreResult> summary() {
        var current =
                slots.get(platform.inventory.getSlots()).container instanceof Inventory inventory
                        ? inventory.player.level().getBlockEntity(platform.getBlockPos())
                        : null;
        return current instanceof MiningBlockEntity be ? be.summary() : platform.summary();
    }

    @Override
    public boolean stillValid(Player player) {
        return platform.getLevel() != null
                && !platform.isRemoved()
                && stillValid(
                        ContainerLevelAccess.create(platform.getLevel(), platform.getBlockPos()),
                        player,
                        StarfoldMining.PLATFORM.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide || !stillValid(player) || id < SCAN || id > TOGGLE_OBSTRUCTIONS)
            return false;
        platform.command(id);
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return MachineMenu.quickMove(
                player, index, slots, platform.inventory.getSlots(), this::moveItemStackTo);
    }
}
