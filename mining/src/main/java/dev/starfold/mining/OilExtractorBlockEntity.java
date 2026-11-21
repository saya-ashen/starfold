package dev.starfold.mining;

import com.mojang.authlib.GameProfile;

import dev.starfold.planet.PlanetCoreBlock;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.planet.PlanetStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Compact, stationary virtual well. No blocks are removed and no ore/fluid blocks are harvested. */
public final class OilExtractorBlockEntity extends BlockEntity implements MenuProvider {
    static final int OFF = 0, SURVEY = 1, BUILD = 2, PUMP = 3;
    private static final TagKey<Block> ORES =
            TagKey.create(Registries.BLOCK, ResourceLocation.parse("c:ores"));
    final OilInventory inventory = new OilInventory(this::setChanged);
    private int energy, burnTicks, survey, depth, clock, mode, lastKnown;
    private boolean surveyed, well;
    private OilStatus status = OilStatus.IDLE;
    private UUID owner;
    private BlockPos anchor;
    private String anchorDimension = "";
    private FakePlayer operator;
    private int revision;
    private boolean accounting;

    /** There is no comparator output. Mark only the loaded owning chunk dirty: vanilla
     * setChanged also walks two blocks into neighbors, which can load a chunk across a border.
     * During a transfer defer even dirty marking until both tank and reservoir are committed. */
    @Override
    public void setChanged() {
        if (!accounting && level != null && level.hasChunkAt(worldPosition))
            level.blockEntityChanged(worldPosition);
    }

    // Kept private: the public capability below cannot fill, even from a null side.
    private final FluidTank tank =
            new FluidTank(
                    OilCycle.TANK_CAPACITY,
                    stack ->
                            BuiltInRegistries.FLUID
                                    .getKey(stack.getFluid())
                                    .equals(
                                            ResourceLocation.parse(
                                                    "createdieselgenerators:crude_oil"))) {
                @Override
                protected void onContentsChanged() {
                    setChanged();
                }
            };
    private final IFluidHandler outlet =
            new IFluidHandler() {
                @Override
                public int getTanks() {
                    return 1;
                }

                @Override
                public FluidStack getFluidInTank(int index) {
                    return index == 0 && !accounting ? tank.getFluid().copy() : FluidStack.EMPTY;
                }

                @Override
                public int getTankCapacity(int index) {
                    return index == 0 ? OilCycle.TANK_CAPACITY : 0;
                }

                @Override
                public boolean isFluidValid(int index, FluidStack stack) {
                    return false;
                }

                @Override
                public int fill(FluidStack stack, FluidAction action) {
                    return 0;
                }

                @Override
                public FluidStack drain(FluidStack stack, FluidAction action) {
                    return accounting ? FluidStack.EMPTY : tank.drain(stack, action);
                }

                @Override
                public FluidStack drain(int amount, FluidAction action) {
                    return accounting ? FluidStack.EMPTY : tank.drain(Math.max(0, amount), action);
                }
            };
    private final IEnergyStorage power =
            new ReceiveOnlyEnergy(
                    OilCycle.ENERGY_CAPACITY,
                    () -> energy,
                    value -> energy = value,
                    this::setChanged);

    public OilExtractorBlockEntity(BlockPos pos, BlockState state) {
        super(StarfoldMining.OIL_BE.get(), pos, state);
    }

    public IItemHandler automation() {
        return inventory.automation;
    }

    public IFluidHandler fluidOutlet() {
        return outlet;
    }

    public IEnergyStorage energyStorage() {
        return power;
    }

    public boolean ownedBy(Player player) {
        return owner != null && owner.equals(player.getUUID());
    }

    public void placedBy(UUID id) {
        owner = id;
        anchor = worldPosition.immutable();
        anchorDimension = level.dimension().location().toString();
        setChanged();
    }

    private boolean canonicalHome(BlockPos pos) {
        long key = PlanetStorage.key(pos.getX() >> 4, pos.getZ() >> 4);
        if (key < 0 || key >= PlanetStorage.CHUNKS_PER_PLANET) return false;
        try {
            var address = PlanetStorage.address(pos);
            return address.owner().planet() == 0 && address.owner().block().equals(pos);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Sable assembly uses separate storage plots, outside owned home chunks. Anchors also forbid relocation. */
    private boolean stationary() {
        return level != null
                && PlanetLevels.is(level)
                && owner != null
                && anchor != null
                && anchor.equals(worldPosition)
                && anchorDimension.equals(level.dimension().location().toString())
                && canonicalHome(worldPosition);
    }

    private boolean online(ServerLevel server) {
        return owner != null && server.getServer().getPlayerList().getPlayer(owner) != null;
    }

    private boolean intact(ServerLevel server, int before) {
        return revision == before
                && !isRemoved()
                && server.hasChunkAt(worldPosition)
                && server.getBlockEntity(worldPosition) == this
                && stationary()
                && online(server);
    }

    public void command(Player player, int action) {
        if (!(level instanceof ServerLevel) || !ownedBy(player)) return;
        if (action == OilMenu.PAUSE) {
            mode = OFF;
            setStatus(OilStatus.PAUSED);
        } else if (action == OilMenu.SURVEY && !well) {
            mode = SURVEY;
            // Resuming an interrupted survey preserves its already-paid progress.
            if (surveyed) {
                survey = 0;
                surveyed = false;
            }
            setStatus(OilStatus.SURVEYING);
        } else if (action == OilMenu.BUILD && surveyed && lastKnown > 0 && !well) {
            mode = BUILD;
            setStatus(OilStatus.BUILDING);
        } else if (action == OilMenu.PUMP && well) {
            mode = PUMP;
            setStatus(OilStatus.PUMPING);
        } else return;
        clock = 0;
        revision++;
        setChanged();
    }

    public void tick() {
        if (!(level instanceof ServerLevel server)) return;
        if (!stationary()) {
            setStatus(OilStatus.UNSUPPORTED);
            return;
        }
        OilStatus missing = CdgOilReservoir.availability();
        if (missing != null) {
            setStatus(missing);
            return;
        }
        // A paused checkpoint is stable while its owner disconnects; active work still pauses.
        if (mode == OFF) return;
        if (!online(server)) {
            setStatus(OilStatus.OWNER_AWAY);
            return;
        }
        if (!available(server, worldPosition)) return;
        int before = revision;
        try {
            if (mode == SURVEY) survey(server, before);
            else if (mode == BUILD) build(server, before);
            else if (mode == PUMP) pump(server, before);
        } catch (RuntimeException | LinkageError failure) {
            // Adapter logs its precise cause. Unexpected machine exceptions must also be visible.
            com.mojang.logging.LogUtils.getLogger()
                    .error("Oil extractor stopped at {}", worldPosition, failure);
            mode = OFF;
            setStatus(OilStatus.CDG_ERROR);
        }
    }

    private OilCycle.Reservoir reservoir(ServerLevel server) {
        // Address resolution above rejects halo aliases. CDG receives only the REAL owned chunk.
        return CdgOilReservoir.at(
                server, new ChunkPos(PlanetStorage.address(worldPosition).owner().block()));
    }

    private void survey(ServerLevel server, int before) {
        if (!canSupply(OilCycle.SURVEY_ENERGY)) return;
        if (!permission(server, worldPosition, server.getBlockState(worldPosition), false)
                || !intact(server, before)) return;
        if (!supply(OilCycle.SURVEY_ENERGY)) return;
        if (!intact(server, before) || mode != SURVEY) return;
        energy -= OilCycle.SURVEY_ENERGY;
        survey++;
        setStatus(OilStatus.SURVEYING);
        if (survey >= OilCycle.SURVEY_TICKS) {
            lastKnown = reservoir(server).read();
            surveyed = true;
            mode = OFF;
            setStatus(lastKnown > 0 ? OilStatus.FOUND : OilStatus.DRY);
        }
        setChanged();
    }

    private void build(ServerLevel server, int before) {
        if (!surveyed || depth >= OilCycle.MAX_DEPTH) {
            stop(OilStatus.BLOCKED);
            return;
        }
        if (!canSupply(OilCycle.WELL_ENERGY)) return;
        if (++clock < 10) {
            setChanged();
            return;
        }
        clock = 0;
        if (!permission(server, worldPosition, server.getBlockState(worldPosition), false)
                || !intact(server, before)) return;
        BlockPos target = worldPosition.below(depth + 1);
        if (!available(server, target)) return;
        BlockState state = server.getBlockState(target);
        boolean bottom = bottom(state);
        if (!bottom && !passable(server, target, state)) return;
        if (!permission(server, target, state, !bottom)
                || !intact(server, before)
                || server.getBlockState(target) != state) return;
        // Recheck all existing virtual rods on every advance; new claims cannot be crossed.
        List<BlockState> path = validatePath(server, depth, false, before);
        if (path == null) return;
        if (server.getBlockState(target) != state || server.getBlockEntity(target) != null) {
            stop(OilStatus.BLOCKED);
            return;
        }
        boolean refuels = mayRefuel(OilCycle.WELL_ENERGY);
        if (!supply(OilCycle.WELL_ENERGY, 10)) return;
        if (refuels && !pathUnchanged(server, path, before)) return;
        if (!intact(server, before) || mode != BUILD) return;
        if (server.getBlockState(target) != state || server.getBlockEntity(target) != null) {
            stop(OilStatus.BLOCKED);
            return;
        }
        energy -= OilCycle.WELL_ENERGY;
        depth++;
        if (bottom) {
            well = true;
            mode = OFF;
            setStatus(OilStatus.WELL_READY);
        } else setStatus(OilStatus.BUILDING);
        setChanged();
    }

    private void pump(ServerLevel server, int before) {
        if (!well) {
            stop(OilStatus.BLOCKED);
            return;
        }
        if (tank.getSpace() <= 0) {
            setStatus(OilStatus.FULL);
            return;
        }
        // CDG recomputes 12,800 biome samples for an unrecorded reservoir. Limit live reads to
        // one attempt per second; the operation's adapter retains that observation through
        // commitment.
        if (++clock < OilCycle.PERIOD) {
            setChanged();
            return;
        }
        clock = 0;
        setChanged();
        if (!canSupply(OilCycle.ENERGY_PER_MB)) return;
        if (!permission(server, worldPosition, server.getBlockState(worldPosition), false)
                || !intact(server, before)) return;
        List<BlockState> path = validatePath(server, depth, true, before);
        if (path == null) return;
        // Claim hooks can synchronously change shared CDG data. Observe only after all
        // external protection callbacks, so this operation never commits an older balance.
        OilCycle.Reservoir source = reservoir(server);
        lastKnown = source.read();
        if (lastKnown == 0) {
            stop(OilStatus.DRY);
            return;
        }
        int desired = Math.min(OilCycle.BATCH, Math.min(tank.getSpace(), lastKnown));
        // Generate this elapsed second's fuel budget only after path validation. This preserves
        // 10 FE per active tick without burning ahead of newly added protection/obstructions.
        int requiredEnergy = desired * OilCycle.ENERGY_PER_MB;
        boolean refuels = mayRefuel(requiredEnergy);
        supply(requiredEnergy, OilCycle.PERIOD);
        // Starting another fuel item calls other mods' burn-time/remainder hooks. Refresh only
        // when those callbacks could have run; ordinary buffered batches keep one live CDG read.
        if (refuels) {
            if (!pathUnchanged(server, path, before) || mode != PUMP) return;
            source = reservoir(server);
            lastKnown = source.read();
            if (lastKnown == 0) {
                stop(OilStatus.DRY);
                return;
            }
        }
        // A partially charged FE buffer can still pay for a smaller, conserved batch.
        if (energy < OilCycle.ENERGY_PER_MB) return;
        if (!intact(server, before) || mode != PUMP) return;
        FluidStack previous = tank.getFluid().copy();
        int accepted;
        accounting = true;
        try {
            accepted =
                    OilCycle.transfer(
                            source,
                            new OilCycle.Receiver() {
                                @Override
                                public int simulate(int requested) {
                                    return tank.fill(
                                            new FluidStack(CdgOilReservoir.fluid(), requested),
                                            IFluidHandler.FluidAction.SIMULATE);
                                }

                                @Override
                                public int receive(int offered) {
                                    return tank.fill(
                                            new FluidStack(CdgOilReservoir.fluid(), offered),
                                            IFluidHandler.FluidAction.EXECUTE);
                                }

                                @Override
                                public void rollback() {
                                    tank.setFluid(previous);
                                }
                            },
                            energy);
            energy -= accepted * OilCycle.ENERGY_PER_MB;
        } finally {
            accounting = false;
        }
        if (lastKnown != Integer.MAX_VALUE) lastKnown = Math.max(0, lastKnown - accepted);
        clock = 0;
        setStatus(accepted > 0 ? OilStatus.PUMPING : OilStatus.FULL);
        setChanged();
    }

    private boolean available(ServerLevel server, BlockPos pos) {
        if (server.isOutsideBuildHeight(pos)
                || !server.getWorldBorder().isWithinBounds(pos)
                || !canonicalHome(pos)) {
            stop(OilStatus.BLOCKED);
            return false;
        }
        if (!server.hasChunkAt(pos)) {
            setStatus(OilStatus.UNLOADED);
            return false;
        }
        return true;
    }

    private static boolean bottom(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || BuiltInRegistries.BLOCK
                        .getKey(state.getBlock())
                        .equals(ResourceLocation.parse("starfold:core_shell"));
    }

    private boolean passable(ServerLevel server, BlockPos pos, BlockState state) {
        boolean terrain =
                state.isAir()
                        || state.is(ORES)
                        || state.is(BlockTags.BASE_STONE_OVERWORLD)
                        || state.is(BlockTags.DIRT)
                        || state.is(Blocks.SAND)
                        || state.is(Blocks.RED_SAND)
                        || state.is(Blocks.GRAVEL)
                        || state.is(Blocks.CLAY)
                        || state.is(Blocks.SNOW_BLOCK)
                        || state.is(Blocks.CALCITE)
                        || state.is(Blocks.TUFF)
                        || state.is(Blocks.SANDSTONE)
                        || state.is(Blocks.RED_SANDSTONE);
        if (!terrain
                || !state.getFluidState().isEmpty()
                || state.hasBlockEntity()
                || server.getBlockEntity(pos) != null
                || state.is(PlanetCoreBlock.IMMUTABLE)
                || state.getDestroySpeed(server, pos) < 0) {
            stop(OilStatus.BLOCKED);
            return false;
        }
        return true;
    }

    private boolean permission(
            ServerLevel server, BlockPos pos, BlockState state, boolean requireDestroyable) {
        if (operator == null)
            operator = new MiningActor(server, new GameProfile(owner, "StarfoldOil"));
        operator.setPos(
                worldPosition.getX() + .5, worldPosition.getY() + 1, worldPosition.getZ() + .5);
        operator.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        if (!server.mayInteract(operator, pos)
                || server.getServer().isUnderSpawnProtection(server, pos, operator)
                || requireDestroyable && !state.canEntityDestroy(server, pos, operator)
                || CommonHooks.fireBlockBreak(server, GameType.SURVIVAL, operator, pos, state)
                        .isCanceled()) {
            stop(OilStatus.PROTECTED);
            return false;
        }
        return true;
    }

    private List<BlockState> validatePath(
            ServerLevel server, int length, boolean terminal, int before) {
        List<BlockState> states = new ArrayList<>(length);
        for (int i = 1; i <= length; i++) {
            BlockPos pos = worldPosition.below(i);
            if (!available(server, pos)) return null;
            BlockState state = server.getBlockState(pos);
            boolean end = terminal && i == length;
            if (end ? !bottom(state) : bottom(state)) {
                stop(OilStatus.BLOCKED);
                return null;
            }
            if (!end && !passable(server, pos, state)) return null;
            // Bedrock/core_shell is a terminal sensor, never destroyed, but still fires the claim
            // hook.
            if (!permission(server, pos, state, !end) || !intact(server, before)) return null;
            states.add(state);
        }
        return pathUnchanged(server, states, before) ? states : null;
    }

    /** Compare the verified route without invoking protection or fuel callbacks again. */
    private boolean pathUnchanged(ServerLevel server, List<BlockState> states, int before) {
        for (int i = 1; i <= states.size(); i++) {
            BlockPos pos = worldPosition.below(i);
            if (!server.hasChunkAt(pos)) {
                setStatus(OilStatus.UNLOADED);
                return false;
            }
            if (server.getBlockState(pos) != states.get(i - 1)
                    || server.getBlockEntity(pos) != null) {
                stop(OilStatus.BLOCKED);
                return false;
            }
        }
        return intact(server, before);
    }

    private boolean mayRefuel(int required) {
        return burnTicks < Math.max(0, (required - energy + 9) / 10);
    }

    /** Read-only preflight: powerless machines do not scan the well or fire protection hooks. */
    private boolean canSupply(int required) {
        if (energy >= required || burnTicks > 0) return true;
        ItemStack fuel = inventory.getStackInSlot(0);
        if (MachineInventory.fuelTime(fuel) <= 0) {
            setStatus(OilStatus.NO_ENERGY);
            return false;
        }
        if (!inventory.canStore(fuel.copyWithCount(1).getCraftingRemainingItem())) {
            setStatus(OilStatus.FUEL_OUTPUT_FULL);
            return false;
        }
        return true;
    }

    /** Demand-driven generator. Full tanks, paused/offline machines and blocked paths burn nothing. */
    private boolean supply(int required) {
        return supply(required, 1);
    }

    private boolean supply(int required, int elapsedTicks) {
        if (energy >= required) return true;
        for (int i = 0;
                i < elapsedTicks && energy < required && energy <= OilCycle.ENERGY_CAPACITY - 10;
                i++) {
            if (burnTicks == 0) {
                ItemStack fuel = inventory.getStackInSlot(0);
                int duration = MachineInventory.fuelTime(fuel);
                if (duration > 0) {
                    ItemStack remainder = fuel.copyWithCount(1).getCraftingRemainingItem();
                    if (!inventory.canStore(remainder)) {
                        setStatus(OilStatus.FUEL_OUTPUT_FULL);
                        return false;
                    }
                    inventory.extractItem(0, 1, false);
                    inventory.store(remainder);
                    burnTicks = Math.min(duration, 1000000);
                }
            }
            if (burnTicks > 0) {
                burnTicks--;
                energy += 10;
                setChanged();
            } else break;
        }
        if (energy < required) {
            setStatus(OilStatus.NO_ENERGY);
            return false;
        }
        return true;
    }

    private void stop(OilStatus why) {
        mode = OFF;
        revision++;
        setStatus(why);
    }

    private void setStatus(OilStatus next) {
        if (status != next) {
            status = next;
            setChanged();
        }
    }

    public void dropContents() {
        for (int i = 0; i < 2; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            inventory.setStackInSlot(i, ItemStack.EMPTY);
            Containers.dropItemStack(
                    level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
        }
        // Breaking the machine discards its tank and work; no portable NBT or duplicate oil drops.
        tank.setFluid(FluidStack.EMPTY);
        energy = burnTicks = 0;
        mode = OFF;
        revision++;
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.starfold_mining.oil_extractor");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new OilMenu(id, inv, this);
    }

    int datum(int index) {
        return switch (index) {
            case 0 -> energy;
            case 1 -> tank.getFluidAmount();
            case 2 -> survey;
            case 3 -> depth;
            case 4 -> mode;
            case 5 -> status.ordinal();
            case 6 -> surveyed ? 1 : 0;
            case 7 -> well ? 1 : 0;
            case 8 -> lastKnown & 0xffff;
            case 9 -> lastKnown >>> 16;
            case 10 -> burnTicks & 0xffff;
            case 11 -> burnTicks >>> 16;
            default -> 0;
        };
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        new OilCycle.Progress(
                        energy,
                        burnTicks,
                        survey,
                        depth,
                        clock,
                        mode,
                        status.ordinal(),
                        lastKnown,
                        surveyed,
                        well)
                .save(tag::putInt, tag::putBoolean);
        if (owner != null) tag.putUUID("Owner", owner);
        if (anchor != null) tag.putLong("Anchor", anchor.asLong());
        tag.putString("AnchorDimension", anchorDimension);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag items = tag.getCompound("Inventory").copy();
        items.putInt("Size", 2);
        inventory.deserializeNBT(registries, items);
        tank.readFromNBT(registries, tag.getCompound("Tank"));
        if (!tank.getFluid().isEmpty() && !tank.isFluidValid(tank.getFluid()))
            tank.setFluid(FluidStack.EMPTY);
        if (tank.getFluidAmount() > OilCycle.TANK_CAPACITY)
            tank.setFluid(tank.getFluid().copyWithAmount(OilCycle.TANK_CAPACITY));
        var progress = OilCycle.Progress.load(tag::getInt, tag::getBoolean);
        energy = progress.energy();
        burnTicks = progress.burnTicks();
        survey = progress.survey();
        depth = progress.depth();
        clock = progress.clock();
        mode = progress.mode();
        status = OilStatus.values()[progress.status()];
        lastKnown = progress.lastKnown();
        surveyed = progress.surveyed();
        well = progress.well();
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        anchor = tag.contains("Anchor") ? BlockPos.of(tag.getLong("Anchor")) : null;
        anchorDimension = tag.getString("AnchorDimension");
        operator = null;
        revision++;
    }
}
