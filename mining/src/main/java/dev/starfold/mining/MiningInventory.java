package dev.starfold.mining;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/** Internal writes use setStackInSlot; all public insertion accepts furnace fuel only. */
final class MiningInventory extends ItemStackHandler {
    private final Runnable changed;

    MiningInventory(Runnable changed) {
        super(19);
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

    final IItemHandler automation = MachineInventory.automation(this, slot -> slot != 0);

    /** Plan the entire drop list against one copy, including contention between drops. */
    List<ItemStack> plan(List<ItemStack> drops) {
        List<ItemStack> result = new ArrayList<>(18);
        for (int i = 1; i < 19; i++) result.add(getStackInSlot(i).copy());
        for (ItemStack drop : drops) {
            ItemStack remaining = drop.copy();
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < 18 && !remaining.isEmpty(); i++) {
                    ItemStack current = result.get(i);
                    if (pass == 0
                            ? current.isEmpty()
                                    || !ItemStack.isSameItemSameComponents(current, remaining)
                            : !current.isEmpty()) continue;
                    int room =
                            Math.min(getSlotLimit(i + 1), remaining.getMaxStackSize())
                                    - current.getCount();
                    int count = Math.min(room, remaining.getCount());
                    if (count <= 0) continue;
                    if (current.isEmpty()) result.set(i, remaining.copyWithCount(count));
                    else current.grow(count);
                    remaining.shrink(count);
                }
            }
            if (!remaining.isEmpty()) return null;
        }
        return result;
    }

    void commit(List<ItemStack> plan) {
        for (int i = 0; i < 18; i++) setStackInSlot(i + 1, plan.get(i));
    }
}
