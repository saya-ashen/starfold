package dev.starfold.mining;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** Inventory layout and transfers only; validity and command authorization belong to each menu. */
final class MachineMenu {
    static void addPlayerSlots(Inventory inventory, Consumer<Slot> addSlot) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot.accept(
                        new Slot(inventory, 9 + row * 9 + col, 8 + col * 18, 151 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot.accept(new Slot(inventory, col, 8 + col * 18, 209));
    }

    @FunctionalInterface
    interface Transfer {
        boolean move(ItemStack stack, int start, int end, boolean reverse);
    }

    static ItemStack quickMove(
            Player player, int index, List<Slot> slots, int machineSlots, Transfer transfer) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        int hotbar = machineSlots + 27;
        if (index < machineSlots) {
            if (!transfer.move(stack, machineSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else if (MachineInventory.fuelTime(stack) > 0) {
            if (!transfer.move(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else if (index < hotbar) {
            if (!transfer.move(stack, hotbar, hotbar + 9, false)) return ItemStack.EMPTY;
        } else if (!transfer.move(stack, machineSlots, hotbar, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    private MachineMenu() {}
}
