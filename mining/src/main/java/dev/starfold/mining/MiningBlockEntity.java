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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

public final class MiningBlockEntity extends BlockEntity implements MenuProvider {
    public static final int IDLE = 0,
            SCANNING = 1,
            READY = 2,
            MINING = 3,
            PAUSED = 4,
            NO_ENERGY = 5,
            UNLOADED = 6,
            OUTPUT_FULL = 7,
            BLOCKED = 8,
            COMPLETE = 9,
            UNSUPPORTED = 10,
            PROTECTED = 11,
            OWNER_AWAY = 12;
    public static final int MAX_ENERGY = 20000,
            WIDTH = MiningVolume.WIDTH,
            DEPTH = MiningVolume.DEPTH,
            TOTAL = MiningVolume.TOTAL;
    public static Consumer<MiningBlockEntity> SCAN_COMPLETED = be -> {};
    private static final int DIG_ENERGY = 80, FE_PER_BURN_TICK = 10;
    private static final TagKey<Block> ORES =
            TagKey.create(Registries.BLOCK, ResourceLocation.parse("c:ores"));

    public record OreResult(String id, int count, int minDepth, int maxDepth) {}

    enum Obstruction {
        NONE,
        SCAN_TOO_DEEP,
        BUILD_HEIGHT,
        WORLD_BORDER,
        WRONG_CHART,
        FLUID,
        BLOCK_ENTITY,
        UNBREAKABLE,
        UNSUPPORTED_BLOCK,
        TOOL_TIER,
        PROTECTION,
        REMOVAL_FAILED;

        private static final Obstruction[] VALUES = values();

        static Obstruction byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
        }
    }

    private final TreeMap<String, OreResult> ores = new TreeMap<>();
    final MiningInventory inventory = new MiningInventory(this::setChanged);
    private int energy, burnTicks, scanCursor, digCursor, status;
    private Obstruction obstruction = Obstruction.NONE;
    private Block obstructingBlock;
    private boolean enabled, scanning, scanned, skipObstructions;
    private UUID owner;
    private BlockPos anchor;
    private String anchorDimension = "";
    private FakePlayer miner;
    private final IEnergyStorage power =
            new ReceiveOnlyEnergy(
                    MAX_ENERGY, () -> energy, value -> energy = value, this::setChanged);

    public MiningBlockEntity(BlockPos pos, BlockState state) {
        super(StarfoldMining.PLATFORM_BE.get(), pos, state);
    }

    public IItemHandler automation() {
        return inventory.automation;
    }

    public IEnergyStorage energyStorage() {
        return power;
    }

    public int status() {
        return status;
    }

    public List<OreResult> summary() {
        return List.copyOf(ores.values());
    }

    public UUID owner() {
        return owner;
    }

    public boolean scanned() {
        return scanned;
    }

    public boolean enabled() {
        return enabled;
    }

    public int scanProgress() {
        return scanCursor;
    }

    public int cursor() {
        return digCursor;
    }

    public int energy() {
        return energy;
    }

    public void placedBy(UUID owner) {
        this.owner = owner;
        anchor = worldPosition.immutable();
        anchorDimension = level.dimension().location().toString();
        setChanged();
    }

    /** Layer-major traversal: every 25 positions shares a depth; never skip intervening stone. */
    public static BlockPos target(BlockPos platform, int cursor) {
        MiningVolume.Offset offset = MiningVolume.offset(cursor);
        return platform.offset(offset.x(), offset.y(), offset.z());
    }

    static int canonicalPlanet(BlockPos pos) {
        long key = PlanetStorage.key(pos.getX() >> 4, pos.getZ() >> 4);
        return key < 0 ? -1 : (int) (key / PlanetStorage.CHUNKS_PER_PLANET);
    }

    /** Every column must be canonical terrain on the anchor's planet. */
    private boolean stationary(int planet) {
        if (!PlanetLevels.is(level)
                || owner == null
                || anchor == null
                || planet < 0
                || !anchor.equals(worldPosition)
                || !anchorDimension.equals(level.dimension().location().toString())) return false;
        int edge = WIDTH / 2;
        // A well narrower than one chunk touches only the chunks containing its corners.
        for (int x = -edge; x <= edge; x += 2 * edge)
            for (int z = -edge; z <= edge; z += 2 * edge)
                if (canonicalPlanet(worldPosition.offset(x, 0, z)) != planet) return false;
        return true;
    }

    public void command(int id) {
        if (level == null || level.isClientSide) return;
        if (id == MiningMenu.TOGGLE_OBSTRUCTIONS) {
            skipObstructions = !skipObstructions;
        } else if (id == MiningMenu.RESET && !enabled) {
            scanning = scanned = false;
            scanCursor = digCursor = 0;
            ores.clear();
            setStatus(IDLE);
            sync();
        } else if (id == MiningMenu.SCAN && !enabled && !scanned && digCursor == 0) {
            if (level.isOutsideBuildHeight(target(worldPosition, TOTAL - 1))) {
                stop(BLOCKED, Obstruction.SCAN_TOO_DEEP, null);
                return;
            }
            scanning = enabled = true;
            setStatus(SCANNING);
        } else if (id == MiningMenu.START_PAUSE && (scanning || scanned)) {
            enabled = !enabled;
            setStatus(enabled ? (scanning ? SCANNING : READY) : PAUSED);
        }
        setChanged();
    }

    public void tick() {
        if (!(level instanceof ServerLevel server)) return;
        int planet = canonicalPlanet(worldPosition);
        if (!stationary(planet)) {
            setStatus(UNSUPPORTED);
            return;
        }
        if (!enabled) {
            if (energy <= MAX_ENERGY - FE_PER_BURN_TICK) fuel(energy + FE_PER_BURN_TICK);
            return;
        }
        var player = server.getServer().getPlayerList().getPlayer(owner);
        if (player == null) {
            setStatus(OWNER_AWAY);
            return;
        }
        if (scanning) {
            for (int i = 0; i < 64 && scanCursor < TOTAL; i++) {
                BlockPos pos = target(worldPosition, scanCursor);
                if (!available(server, planet, pos)) return;
                if (energy < 2) fuel(2);
                if (energy < 2) {
                    setStatus(NO_ENERGY);
                    return;
                }
                BlockState state = server.getBlockState(pos);
                if (state.is(ORES)) {
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    int depth = scanCursor / MiningVolume.LAYER + 1;
                    OreResult old = ores.get(id);
                    ores.put(
                            id,
                            new OreResult(
                                    id,
                                    old == null ? 1 : old.count + 1,
                                    old == null ? depth : old.minDepth,
                                    depth));
                }
                energy -= 2;
                scanCursor++;
                setChanged();
                if (scanCursor % 64 == 0) sync();
            }
            setStatus(SCANNING);
            if (scanCursor == TOTAL) {
                scanned = true;
                scanning = enabled = false;
                setStatus(READY);
                sync();
                SCAN_COMPLETED.accept(this);
            }
        } else if (scanned) {
            if (digCursor >= TOTAL) {
                enabled = false;
                setStatus(COMPLETE);
                return;
            }
            BlockPos pos = target(worldPosition, digCursor);
            if (!available(server, planet, pos)) return;
            dig(server, planet, pos);
        }
    }

    private boolean available(ServerLevel server, int planet, BlockPos pos) {
        if (server.isOutsideBuildHeight(pos)) {
            stop(BLOCKED, Obstruction.BUILD_HEIGHT, null);
            return false;
        }
        if (!server.getWorldBorder().isWithinBounds(pos)) {
            stop(BLOCKED, Obstruction.WORLD_BORDER, null);
            return false;
        }
        if (canonicalPlanet(pos) != planet) {
            stop(BLOCKED, Obstruction.WRONG_CHART, null);
            return false;
        }
        if (!server.hasChunkAt(pos)) {
            setStatus(UNLOADED);
            return false;
        }
        return true;
    }

    private void fuel(int requiredEnergy) {
        while (energy < requiredEnergy) {
            if (burnTicks == 0) {
                ItemStack stack = inventory.getStackInSlot(0);
                int time = MachineInventory.fuelTime(stack);
                if (time == 0) return;
                ItemStack remainder = stack.copyWithCount(1).getCraftingRemainingItem();
                List<ItemStack> plan = inventory.plan(List.of(remainder));
                if (plan == null) return;
                inventory.extractItem(0, 1, false);
                inventory.commit(plan);
                burnTicks = time;
            }
            int consumed =
                    Math.min(
                            burnTicks,
                            (requiredEnergy - energy + FE_PER_BURN_TICK - 1) / FE_PER_BURN_TICK);
            burnTicks -= consumed;
            energy += consumed * FE_PER_BURN_TICK;
            setChanged();
        }
    }

    private static boolean terrain(BlockState state) {
        return state.is(ORES)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.DIRT)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE);
    }

    private void dig(ServerLevel server, int planet, BlockPos pos) {
        // Vanilla removal/shape updates read neighbors too. Keep a loaded chunk halo
        // before sending those updates; hasChunkAt never asks the chunk source to load.
        for (int x = -16; x <= 16; x += 16)
            for (int z = -16; z <= 16; z += 16)
                if (!server.hasChunkAt(pos.offset(x, 0, z))) {
                    setStatus(UNLOADED);
                    return;
                }
        BlockState state = server.getBlockState(pos);
        if (state.isAir()) {
            digCursor++;
            setChanged();
            setStatus(MINING);
            return;
        }
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        Obstruction reason = obstructionAt(server, pos, state, tool);
        if (reason != Obstruction.NONE) {
            if (skipObstructions) {
                digCursor++;
                setChanged();
                setStatus(MINING);
            } else {
                stop(BLOCKED, reason, state.getBlock());
            }
            return;
        }
        if (energy < DIG_ENERGY) fuel(DIG_ENERGY);
        if (energy < DIG_ENERGY) {
            setStatus(NO_ENERGY);
            return;
        }
        int cursorBeforeEvent = digCursor;
        if (miner == null) miner = new MiningActor(server, new GameProfile(owner, "StarfoldMiner"));
        miner.setPos(
                worldPosition.getX() + .5, worldPosition.getY() + 1, worldPosition.getZ() + .5);
        miner.setItemSlot(EquipmentSlot.MAINHAND, tool);
        if (!server.mayInteract(miner, pos)
                || server.getServer().isUnderSpawnProtection(server, pos, miner)
                || !state.canEntityDestroy(server, pos, miner)
                || CommonHooks.fireBlockBreak(server, GameType.SURVIVAL, miner, pos, state)
                        .isCanceled()) {
            stop(PROTECTED, Obstruction.PROTECTION, state.getBlock());
            return;
        }
        // Events may alter the target: never apply old loot to a replacement block.
        if (server.getBlockState(pos) != state
                || server.getBlockEntity(pos) != null
                || isRemoved()
                || !enabled
                || !scanned
                || digCursor != cursorBeforeEvent
                || energy < DIG_ENERGY
                || !stationary(planet)) return;
        List<ItemStack> drops = Block.getDrops(state, server, pos, null, miner, tool);
        List<ItemStack> plan = inventory.plan(drops);
        if (plan == null) {
            setStatus(OUTPUT_FULL);
            return;
        }
        if (!server.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
            stop(PROTECTED, Obstruction.REMOVAL_FAILED, state.getBlock());
            return;
        }
        inventory.commit(plan);
        energy -= DIG_ENERGY;
        digCursor++;
        setChanged();
        setStatus(MINING);
        server.levelEvent(2001, pos, Block.getId(state));
    }

    private static Obstruction obstructionAt(
            ServerLevel server, BlockPos pos, BlockState state, ItemStack tool) {
        if (!state.getFluidState().isEmpty()) return Obstruction.FLUID;
        if (state.hasBlockEntity()) return Obstruction.BLOCK_ENTITY;
        if (state.is(PlanetCoreBlock.IMMUTABLE) || state.getDestroySpeed(server, pos) < 0)
            return Obstruction.UNBREAKABLE;
        if (!terrain(state)) return Obstruction.UNSUPPORTED_BLOCK;
        if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state))
            return Obstruction.TOOL_TIER;
        return Obstruction.NONE;
    }

    private void stop(int next, Obstruction reason, Block block) {
        enabled = false;
        status = next;
        obstruction = reason;
        obstructingBlock = block;
        setChanged();
    }

    private void setStatus(int next) {
        if (status != next) {
            status = next;
            obstruction = Obstruction.NONE;
            obstructingBlock = null;
            setChanged();
        }
    }

    public void dropContents() {
        for (int i = 0; i < 19; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            inventory.setStackInSlot(i, ItemStack.EMPTY);
            Containers.dropItemStack(
                    level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
        }
        energy = burnTicks = 0;
        enabled = false;
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.starfold_mining.mining_platform");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new MiningMenu(id, inventory, this);
    }

    int datum(int index) {
        return switch (index) {
            case 0 -> energy;
            case 1 -> MAX_ENERGY;
            case 2 -> digCursor;
            case 3 -> scanCursor;
            case 4 -> status;
            case 5 -> enabled ? 1 : 0;
            case 6 -> scanned ? 1 : 0;
            case 7 -> TOTAL;
            case 8 -> obstruction.ordinal();
            case 9 -> obstructingBlockId() & 0xffff;
            case 10 -> obstructingBlockId() >>> 16;
            case 11 -> skipObstructions ? 1 : 0;
            default -> 0;
        };
    }

    private int obstructingBlockId() {
        return obstructingBlock == null ? -1 : BuiltInRegistries.BLOCK.getId(obstructingBlock);
    }

    private void writeResults(CompoundTag tag) {
        ListTag list = new ListTag();
        for (OreResult ore : ores.values()) {
            CompoundTag row = new CompoundTag();
            row.putString("Id", ore.id);
            row.putInt("Count", ore.count);
            row.putInt("Min", ore.minDepth);
            row.putInt("Max", ore.maxDepth);
            list.add(row);
        }
        tag.put("Ores", list);
    }

    private void readResults(CompoundTag tag) {
        ores.clear();
        ListTag list = tag.getList("Ores", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(TOTAL, list.size()); i++) {
            CompoundTag row = list.getCompound(i);
            String id = row.getString("Id");
            if (ResourceLocation.tryParse(id) != null)
                ores.put(
                        id,
                        new OreResult(
                                id,
                                Math.clamp(row.getInt("Count"), 0, TOTAL),
                                Math.clamp(row.getInt("Min"), 1, DEPTH),
                                Math.clamp(row.getInt("Max"), 1, DEPTH)));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.putInt("Energy", energy);
        tag.putInt("BurnTicks", burnTicks);
        tag.putInt("ScanCursor", scanCursor);
        tag.putInt("DigCursor", digCursor);
        tag.putInt("Status", status);
        if (obstruction != Obstruction.NONE) tag.putInt("Obstruction", obstruction.ordinal());
        if (obstructingBlock != null)
            tag.putString(
                    "ObstructingBlock", BuiltInRegistries.BLOCK.getKey(obstructingBlock).toString());
        tag.putBoolean("Enabled", enabled);
        tag.putBoolean("Scanning", scanning);
        tag.putBoolean("Scanned", scanned);
        tag.putBoolean("SkipObstructions", skipObstructions);
        if (owner != null) tag.putUUID("Owner", owner);
        if (anchor != null) tag.putLong("Anchor", anchor.asLong());
        tag.putString("AnchorDimension", anchorDimension);
        writeResults(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag items = tag.getCompound("Inventory").copy();
        items.putInt("Size", 19);
        inventory.deserializeNBT(registries, items);
        energy = Math.clamp(tag.getInt("Energy"), 0, MAX_ENERGY);
        burnTicks = Math.max(0, tag.getInt("BurnTicks"));
        scanCursor = Math.clamp(tag.getInt("ScanCursor"), 0, TOTAL);
        digCursor = Math.clamp(tag.getInt("DigCursor"), 0, TOTAL);
        status = Math.clamp(tag.getInt("Status"), IDLE, OWNER_AWAY);
        obstruction = Obstruction.byId(tag.getInt("Obstruction"));
        ResourceLocation blockId = ResourceLocation.tryParse(tag.getString("ObstructingBlock"));
        obstructingBlock =
                blockId == null ? null : BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null);
        enabled = tag.getBoolean("Enabled");
        scanning = tag.getBoolean("Scanning");
        scanned = tag.getBoolean("Scanned") && scanCursor == TOTAL;
        skipObstructions = tag.getBoolean("SkipObstructions");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        anchor = tag.contains("Anchor") ? BlockPos.of(tag.getLong("Anchor")) : null;
        anchorDimension = tag.getString("AnchorDimension");
        readResults(tag);
        miner = null;
    }

    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide)
            level.sendBlockUpdated(
                    worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        writeResults(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readResults(tag);
    }

    @Override
    public void onDataPacket(
            Connection connection,
            ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries) {
        if (packet.getTag() != null) readResults(packet.getTag());
    }
}
