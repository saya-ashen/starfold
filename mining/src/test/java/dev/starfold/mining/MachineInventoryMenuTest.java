package dev.starfold.mining;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class MachineInventoryMenuTest {
    // Plain module unit tests bootstrap registries, without loading fuel data maps or a game.
    // Exercise the patched fuel API using a scoped event fixture instead.
    private static final Consumer<FurnaceFuelBurnTimeEvent> FUEL =
            event -> {
                if (event.getItemStack().is(Items.COAL)) {
                    assertEquals(RecipeType.SMELTING, event.getRecipeType());
                    event.setBurnTime(1600);
                }
            };

    @BeforeAll
    static void bootstrap() {
        if (LoadingModList.get() == null)
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NeoForge.EVENT_BUS.start();
        NeoForge.EVENT_BUS.addListener(FurnaceFuelBurnTimeEvent.class, FUEL);
    }

    @AfterAll
    static void removeFuelFixture() {
        NeoForge.EVENT_BUS.unregister(FUEL);
    }

    @Test
    void automationKeepsFuelAndOutputsSeparateAndSimulationDoesNotNotify() {
        AtomicInteger changes = new AtomicInteger();
        var mining = new MiningInventory(changes::incrementAndGet);
        var oil = new OilInventory(changes::incrementAndGet);
        assertEquals(19, mining.automation.getSlots());
        assertEquals(2, oil.automation.getSlots());
        checkAutomation(mining, mining.automation, changes);
        checkAutomation(oil, oil.automation, changes);
    }

    private static void checkAutomation(
            ItemStackHandler inventory, IItemHandler automation, AtomicInteger changes) {
        int before = changes.get();
        assertEquals(1600, MachineInventory.fuelTime(new ItemStack(Items.COAL)));
        assertTrue(automation.isItemValid(0, new ItemStack(Items.COAL)));
        assertFalse(automation.isItemValid(0, new ItemStack(Items.STONE)));
        assertTrue(automation.insertItem(0, new ItemStack(Items.COAL, 2), true).isEmpty());
        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertEquals(before, changes.get());
        assertTrue(automation.insertItem(0, new ItemStack(Items.COAL, 2), false).isEmpty());
        assertEquals(before + 1, changes.get());
        assertTrue(automation.extractItem(0, 1, false).isEmpty());
        assertEquals(2, inventory.getStackInSlot(0).getCount());
        assertEquals(1, automation.insertItem(0, new ItemStack(Items.STONE), false).getCount());
        assertEquals(before + 1, changes.get());
        for (int output = 1; output < inventory.getSlots(); output++) {
            assertFalse(automation.isItemValid(output, new ItemStack(Items.COAL)));
            assertEquals(
                    1, automation.insertItem(output, new ItemStack(Items.COAL), false).getCount());
            inventory.setStackInSlot(output, new ItemStack(Items.STONE, 3));
            int afterWrite = changes.get();
            assertEquals(1, automation.extractItem(output, 1, true).getCount());
            assertEquals(3, inventory.getStackInSlot(output).getCount());
            assertEquals(afterWrite, changes.get());
            assertEquals(1, automation.extractItem(output, 1, false).getCount());
            assertEquals(2, inventory.getStackInSlot(output).getCount());
            assertEquals(afterWrite + 1, changes.get());
        }
    }

    @Test
    void miningPlansAllDropsAgainstSharedCapacityBeforeCommit() {
        AtomicInteger changes = new AtomicInteger();
        var inventory = new MiningInventory(changes::incrementAndGet);
        for (int slot = 1; slot < 19; slot++)
            inventory.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
        inventory.setStackInSlot(1, new ItemStack(Items.STONE, 63));
        int before = changes.get();
        assertNull(inventory.plan(List.of(new ItemStack(Items.STONE), new ItemStack(Items.STONE))));
        assertEquals(63, inventory.getStackInSlot(1).getCount());
        assertEquals(before, changes.get());
        var plan = inventory.plan(List.of(new ItemStack(Items.STONE)));
        assertNotNull(plan);
        assertEquals(63, inventory.getStackInSlot(1).getCount());
        assertEquals(before, changes.get());
        inventory.commit(plan);
        assertEquals(64, inventory.getStackInSlot(1).getCount());
    }

    @Test
    void oilRemainderChecksRespectItemIdentityAndItsOwnStackLimit() {
        AtomicInteger changes = new AtomicInteger();
        var inventory = new OilInventory(changes::incrementAndGet);
        inventory.store(new ItemStack(Items.BUCKET, 15));
        int before = changes.get();
        assertTrue(inventory.canStore(ItemStack.EMPTY));
        assertTrue(inventory.canStore(new ItemStack(Items.BUCKET)));
        assertFalse(inventory.canStore(new ItemStack(Items.BUCKET, 2)));
        assertFalse(inventory.canStore(new ItemStack(Items.STONE)));
        assertEquals(before, changes.get());
        inventory.store(new ItemStack(Items.BUCKET));
        assertEquals(16, inventory.getStackInSlot(1).getCount());
        assertFalse(inventory.canStore(new ItemStack(Items.BUCKET)));
    }

    @Test
    void bothMenuSizesRouteMachineFuelAndPlayerTransfersAtTheirBoundaries() {
        for (int machineSlots : new int[] {19, 2}) {
            int hotbar = machineSlots + 27, total = hotbar + 9;
            assertRoute(machineSlots, 0, Items.COAL, machineSlots, total, true);
            assertRoute(machineSlots, machineSlots - 1, Items.STONE, machineSlots, total, true);
            assertRoute(machineSlots, machineSlots, Items.COAL, 0, 1, false);
            assertRoute(machineSlots, total - 1, Items.COAL, 0, 1, false);
            assertRoute(machineSlots, machineSlots, Items.STONE, hotbar, total, false);
            assertRoute(machineSlots, hotbar - 1, Items.STONE, hotbar, total, false);
            assertRoute(machineSlots, hotbar, Items.STONE, machineSlots, hotbar, false);
            assertRoute(machineSlots, total - 1, Items.STONE, machineSlots, hotbar, false);
        }
    }

    private static void assertRoute(
            int machineSlots,
            int index,
            net.minecraft.world.item.Item item,
            int expectedStart,
            int expectedEnd,
            boolean expectedReverse) {
        var container = new SimpleContainer(machineSlots + 36);
        var slots = slots(container);
        container.setItem(index, new ItemStack(item, 3));
        AtomicInteger calls = new AtomicInteger();
        var moved =
                MachineMenu.quickMove(
                        null,
                        index,
                        slots,
                        machineSlots,
                        (stack, start, end, reverse) -> {
                            calls.incrementAndGet();
                            assertEquals(expectedStart, start);
                            assertEquals(expectedEnd, end);
                            assertEquals(expectedReverse, reverse);
                            stack.shrink(1);
                            return true;
                        });
        assertEquals(1, calls.get());
        assertEquals(3, moved.getCount());
        assertEquals(2, container.getItem(index).getCount());
    }

    @Test
    void rejectedFuelTransferDoesNotFallbackAndEmptyOrInvalidSlotsDoNotTransfer() {
        for (int machineSlots : new int[] {19, 2}) {
            var container = new SimpleContainer(machineSlots + 36);
            var slots = slots(container);
            container.setItem(machineSlots, new ItemStack(Items.COAL, 3));
            AtomicInteger calls = new AtomicInteger();
            assertTrue(
                    MachineMenu.quickMove(
                                    null,
                                    machineSlots,
                                    slots,
                                    machineSlots,
                                    (stack, start, end, reverse) -> {
                                        calls.incrementAndGet();
                                        assertEquals(0, start);
                                        assertEquals(1, end);
                                        return false;
                                    })
                            .isEmpty());
            assertEquals(1, calls.get());
            assertEquals(3, container.getItem(machineSlots).getCount());
            for (int index : new int[] {-1, slots.size(), 0})
                assertTrue(
                        MachineMenu.quickMove(
                                        null,
                                        index,
                                        slots,
                                        machineSlots,
                                        (stack, start, end, reverse) -> {
                                            fail("Empty/invalid source must not transfer");
                                            return false;
                                        })
                                .isEmpty());
        }
    }

    @Test
    void completeTransferClearsSourceAndReturnsOriginalStack() {
        var container = new SimpleContainer(38);
        container.setItem(1, new ItemStack(Items.STONE, 3));
        var result =
                MachineMenu.quickMove(
                        null,
                        1,
                        slots(container),
                        2,
                        (stack, start, end, reverse) -> {
                            stack.setCount(0);
                            return true;
                        });
        assertEquals(3, result.getCount());
        assertTrue(container.getItem(1).isEmpty());
    }

    private static List<Slot> slots(SimpleContainer container) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++)
            slots.add(new Slot(container, i, 0, 0));
        return slots;
    }

    @Test
    void playerLayoutKeepsAllInventoryAndHotbarIndicesAndCoordinates() {
        List<Slot> slots = new ArrayList<>();
        MachineMenu.addPlayerSlots(new Inventory(null), slots::add);
        assertEquals(36, slots.size());
        for (int i = 0; i < 36; i++) {
            var slot = slots.get(i);
            assertEquals(i < 27 ? i + 9 : i - 27, slot.getContainerSlot());
            assertEquals(8 + i % 9 * 18, slot.x);
            assertEquals(i < 27 ? 151 + i / 9 * 18 : 209, slot.y);
        }
    }
}
