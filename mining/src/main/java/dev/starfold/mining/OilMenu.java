package dev.starfold.mining;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

public final class OilMenu extends AbstractContainerMenu {
    public static final int SURVEY = 0, BUILD = 1, PUMP = 2, PAUSE = 3;
    private final OilExtractorBlockEntity machine;
    private final ContainerData data;

    public OilMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, clientMachine(inventory, buffer));
    }

    private static OilExtractorBlockEntity clientMachine(
            Inventory inventory, FriendlyByteBuf buffer) {
        var pos = buffer.readBlockPos();
        var found = inventory.player.level().getBlockEntity(pos);
        return found instanceof OilExtractorBlockEntity be
                ? be
                : new OilExtractorBlockEntity(pos, StarfoldMining.OIL.get().defaultBlockState());
    }

    public OilMenu(int id, Inventory inventory, OilExtractorBlockEntity machine) {
        super(StarfoldMining.OIL_MENU.get(), id);
        this.machine = machine;
        data =
                inventory.player.level().isClientSide
                        ? new SimpleContainerData(12)
                        : new ContainerData() {
                            @Override
                            public int get(int index) {
                                return machine.datum(index);
                            }

                            @Override
                            public void set(int index, int value) {}

                            @Override
                            public int getCount() {
                                return 12;
                            }
                        };
        addDataSlots(data);
        addSlot(new SlotItemHandler(machine.inventory, 0, 25, 65));
        addSlot(
                new SlotItemHandler(machine.inventory, 1, 25, 102) {
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

    public int oil() {
        return data.get(1);
    }

    public int survey() {
        return data.get(2);
    }

    public int depth() {
        return data.get(3);
    }

    public int mode() {
        return data.get(4);
    }

    OilStatus status() {
        return OilStatus.values()[Math.clamp(data.get(5), 0, OilStatus.values().length - 1)];
    }

    public boolean surveyed() {
        return data.get(6) != 0;
    }

    public boolean well() {
        return data.get(7) != 0;
    }

    // ContainerData packets are signed shorts: explicitly reassemble both halves.
    public int remaining() {
        return (data.get(8) & 0xffff) | (data.get(9) & 0xffff) << 16;
    }

    public int burnTicks() {
        return (data.get(10) & 0xffff) | (data.get(11) & 0xffff) << 16;
    }

    @Override
    public boolean stillValid(Player player) {
        return machine.getLevel() != null
                && !machine.isRemoved()
                && stillValid(
                        ContainerLevelAccess.create(machine.getLevel(), machine.getBlockPos()),
                        player,
                        StarfoldMining.OIL.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int action) {
        if (player.level().isClientSide
                || !stillValid(player)
                || !machine.ownedBy(player)
                || action < SURVEY
                || action > PAUSE) return false;
        machine.command(player, action);
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return MachineMenu.quickMove(
                player, index, slots, machine.inventory.getSlots(), this::moveItemStackTo);
    }
}
