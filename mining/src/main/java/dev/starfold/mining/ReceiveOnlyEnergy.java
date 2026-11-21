package dev.starfold.mining;

import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Capability view over machine-owned energy; fuel, spending and persistence remain machine-local. */
final class ReceiveOnlyEnergy implements IEnergyStorage {
    private final int capacity;
    private final IntSupplier stored;
    private final IntConsumer store;
    private final Runnable changed;

    ReceiveOnlyEnergy(int capacity, IntSupplier stored, IntConsumer store, Runnable changed) {
        this.capacity = capacity;
        this.stored = stored;
        this.store = store;
        this.changed = changed;
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        int energy = stored.getAsInt();
        int received = Math.min(Math.max(0, amount), capacity - energy);
        if (!simulate && received > 0) {
            store.accept(energy + received);
            changed.run();
        }
        return received;
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return stored.getAsInt();
    }

    @Override
    public int getMaxEnergyStored() {
        return capacity;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
