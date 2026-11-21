package dev.starfold.mining;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.function.IntPredicate;

/** Shared fuel policy and restricted automation view; internal output writes stay with each machine. */
final class MachineInventory {
    static int fuelTime(ItemStack stack) {
        return stack.getBurnTime(RecipeType.SMELTING);
    }

    static boolean isFuelInput(int slot, ItemStack stack) {
        return slot == 0 && fuelTime(stack) > 0;
    }

    static IItemHandler automation(IItemHandler inventory, IntPredicate outputSlot) {
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return inventory.getSlots();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return inventory.getStackInSlot(slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return slot == 0 ? inventory.insertItem(slot, stack, simulate) : stack;
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return outputSlot.test(slot)
                        ? inventory.extractItem(slot, amount, simulate)
                        : ItemStack.EMPTY;
            }

            @Override
            public int getSlotLimit(int slot) {
                return inventory.getSlotLimit(slot);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return inventory.isItemValid(slot, stack);
            }
        };
    }

    private MachineInventory() {}
}
