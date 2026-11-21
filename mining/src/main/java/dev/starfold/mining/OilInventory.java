package dev.starfold.mining;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Fuel input and container remainder output; automation cannot steal burning input. */
final class OilInventory extends ItemStackHandler {
    private final Runnable changed;

    OilInventory(Runnable changed) {
        super(2);
        this.changed = changed;
    }

    @Override
    protected void onContentsChanged(int slot) {
        changed.run();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return MachineInventory.isFuelInput(slot, stack);
    }

    boolean canStore(ItemStack remainder) {
        ItemStack current = getStackInSlot(1);
        return remainder.isEmpty()
                || (current.isEmpty() || ItemStack.isSameItemSameComponents(current, remainder))
                        && current.getCount() + remainder.getCount()
                                <= Math.min(getSlotLimit(1), remainder.getMaxStackSize());
    }

    void store(ItemStack remainder) {
        if (remainder.isEmpty()) return;
        ItemStack current = getStackInSlot(1).copy();
        if (current.isEmpty()) current = remainder.copy();
        else current.grow(remainder.getCount());
        setStackInSlot(1, current);
    }

    final IItemHandler automation = MachineInventory.automation(this, slot -> slot == 1);
}
