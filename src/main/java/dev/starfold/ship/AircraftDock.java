package dev.starfold.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

/** Stored vehicles are data, never live entities in a hull plot. Optional hull linkage lives only in Hull. */
public final class AircraftDock {
    static final int SETTLE_TICKS = 20;

    /** A vehicle at rest on a hull is not exactly on its deck: the hull's pose trembles and Sable's collision leaves it a little in or over the plate. */
    static final double SINK = .5;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-dock");
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("starfold");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("starfold");
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "starfold");
    public static Block block;
    public static BlockEntityType<Dock> type;

    public static void initialize(IEventBus bus) {
        BLOCKS.register(
                "aircraft_dock",
                () ->
                        block =
                                new DockBlock(
                                        Block.Properties.of()
                                                .mapColor(
                                                        net.minecraft.world.level.material.MapColor
                                                                .METAL)
                                                .sound(
                                                        net.minecraft.world.level.block.SoundType
                                                                .METAL)
                                                .strength(2)
                                                .requiresCorrectToolForDrops()));
        ITEMS.register("aircraft_dock", () -> new BlockItem(block, new Item.Properties()));
        ENTITIES.register(
                "aircraft_dock",
                () -> type = BlockEntityType.Builder.of(Dock::new, block).build(null));
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(
                (net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
                    if (e.getTabKey()
                            .equals(net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS))
                        e.accept(block);
                });
        // Sneaking with an item normally bypasses block interaction; an occupied dock must still
        // release, not place/use the item.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.bus.api.EventPriority.LOW,
                (net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock
                                e) -> {
                    if (e.getUseBlock() != net.neoforged.neoforge.common.util.TriState.FALSE
                            && e.getLevel().getBlockEntity(e.getPos()) instanceof Dock dock
                            && dock.stored != null) {
                        e.setUseBlock(net.neoforged.neoforge.common.util.TriState.TRUE);
                        e.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
                    }
                });
        if (ModList.get().isLoaded("sable")) Hull.initialize();
    }

    /** Centre is the top face, in LEVEL coordinates; orientation maps dock axes into LEVEL axes. */
    record Frame(Vec3 centre, Quaterniond rotation) {
        Vec3 local(Vec3 point) {
            var v =
                    rotation.transformInverse(
                            new Vector3d(
                                    point.x - centre.x, point.y - centre.y, point.z - centre.z));
            return new Vec3(v.x, v.y, v.z);
        }

        float yaw() {
            var v = rotation.transform(new Vector3d(0, 0, 1));
            return (float) Math.toDegrees(Math.atan2(-v.x, v.z));
        }
    }

    static boolean zone(Vec3 local) {
        return Double.isFinite(local.x)
                && Double.isFinite(local.y)
                && Double.isFinite(local.z)
                && local.x * local.x + local.z * local.z <= 2.25 + 1e-9
                && local.y >= -SINK
                && local.y <= 2 + 1e-9;
    }

    static int settled(
            int previous, boolean same, boolean inside, boolean passenger, boolean suppressed) {
        return !inside || passenger || suppressed
                ? 0
                : Math.min(SETTLE_TICKS, (same ? previous : 0) + 1);
    }

    static boolean suppressed(boolean previous, boolean inside) {
        return previous && inside;
    }

    static Frame frame(Level level, BlockPos pos) {
        return ModList.get().isLoaded("sable")
                ? Hull.frame(level, pos)
                : new Frame(Vec3.atBottomCenterOf(pos.above()), new Quaterniond());
    }

    static boolean eligible(Entity entity) {
        return entity instanceof VehicleEntity
                && !(entity instanceof AbstractMinecart)
                && entity.isAlive()
                && !entity.isPassenger();
    }

    static final class DockBlock extends Block implements EntityBlock {
        DockBlock(Properties properties) {
            super(properties);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Dock(pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
                Level level, BlockState state, BlockEntityType<T> kind) {
            return !level.isClientSide && kind == type ? (l, p, s, e) -> ((Dock) e).tick() : null;
        }

        @Override
        protected net.minecraft.world.ItemInteractionResult useItemOn(
                net.minecraft.world.item.ItemStack item,
                BlockState state,
                Level level,
                BlockPos pos,
                Player player,
                net.minecraft.world.InteractionHand hand,
                BlockHitResult hit) {
            if (level.getBlockEntity(pos) instanceof Dock dock && dock.stored != null) {
                useWithoutItem(state, level, pos, player, hit);
                return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        @Override
        protected InteractionResult useWithoutItem(
                BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!(level.getBlockEntity(pos) instanceof Dock dock) || dock.stored == null)
                return InteractionResult.PASS;
            if (!level.isClientSide) dock.release(player, !player.isShiftKeyDown());
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected void onRemove(
                BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
            if (!state.is(next.getBlock())
                    && !level.isClientSide
                    && level.getBlockEntity(pos) instanceof Dock dock
                    && dock.stored != null
                    && !dock.release(null, false)) {
                // A missing/broken mod must not eat the only copy on block destruction. Drop a
                // recovery dock with untouched NBT.
                var recovery = new net.minecraft.world.item.ItemStack(block);
                dock.saveToItem(recovery, level.registryAccess());
                popResource(level, pos, recovery);
            }
            super.onRemove(state, level, pos, next, moving);
        }
    }

    static final class Dock extends BlockEntity {
        CompoundTag stored;
        float yaw;
        UUID candidate;
        final java.util.Set<UUID> ignored = new java.util.HashSet<>();
        int quiet;
        long lastTick = Long.MIN_VALUE, revision;

        Dock(BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        void tick() {
            if (!(level instanceof ServerLevel server) || lastTick == level.getGameTime()) return;
            lastTick = level.getGameTime();
            var frame = frame(level, worldPosition);
            if (ignored.removeIf(
                    id -> {
                        var e = server.getEntity(id);
                        return e != null && !suppressed(true, zone(frame.local(e.position())));
                    })) setChanged();
            if (stored != null) return;
            // Radius encloses the rotated cylinder. Query LEVEL entities, never entities inside
            // plot storage.
            var c = frame.centre;
            var vehicles =
                    level.getEntitiesOfClass(
                            VehicleEntity.class, new AABB(c, c).inflate(4), AircraftDock::eligible);
            Entity found = null;
            for (var e : vehicles)
                if (!e.isVehicle()
                        && !ignored.contains(e.getUUID())
                        && zone(frame.local(e.position()))) {
                    if (found == null || e.getUUID().equals(candidate)) found = e;
                }
            if (level.getGameTime() % 20 == 0 && !vehicles.isEmpty() && LOG.isDebugEnabled())
                for (var e : vehicles)
                    LOG.debug(
                            "dock {} sees {} local {} zone {} passengers {} ignored {} quiet {}",
                            worldPosition,
                            e.getType(),
                            frame.local(e.position()),
                            zone(frame.local(e.position())),
                            e.isVehicle(),
                            ignored.contains(e.getUUID()),
                            quiet);
            quiet =
                    settled(
                            quiet,
                            found != null && found.getUUID().equals(candidate),
                            found != null,
                            found != null && found.isVehicle(),
                            found != null && ignored.contains(found.getUUID()));
            candidate = found == null ? null : found.getUUID();
            if (quiet < SETTLE_TICKS) return;
            var data = new CompoundTag();
            if (!found.save(data)) {
                quiet = 0;
                return;
            }
            stored = data;
            yaw = net.minecraft.util.Mth.wrapDegrees(found.getYRot() - frame.yaw());
            found.discard();
            candidate = null;
            quiet = 0;
            sync();
        }

        boolean release(Player player, boolean board) {
            if (!(level instanceof ServerLevel server) || stored == null) return false;
            var frame = frame(level, worldPosition);
            Entity entity;
            try {
                entity = EntityType.create(stored.copy(), level).orElse(null);
            } catch (RuntimeException | LinkageError failure) {
                entity = null;
            }
            if (entity == null) {
                if (player != null)
                    player.displayClientMessage(
                            Component.translatable("message.starfold.aircraft_dock.missing_mod"),
                            true);
                return false;
            }
            var c = frame.centre;
            entity.moveTo(c.x, c.y, c.z, net.minecraft.util.Mth.wrapDegrees(yaw + frame.yaw()), 0);
            entity.setDeltaMovement(Vec3.ZERO);
            if (!server.addFreshEntity(entity)) return false;
            stored = null;
            candidate = null;
            quiet = 0;
            if (!(board && player != null && player.startRiding(entity)))
                ignored.add(entity.getUUID());
            sync();
            return true;
        }

        void sync() {
            revision++;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            if (ModList.get().isLoaded("sable")) Hull.sync(this);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.saveAdditional(tag, lookup);
            if (stored != null) tag.put("Vehicle", stored.copy());
            tag.putFloat("Yaw", yaw);
            var released = new net.minecraft.nbt.ListTag();
            for (var id : ignored) released.add(net.minecraft.nbt.NbtUtils.createUUID(id));
            tag.put("Released", released);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
            super.loadAdditional(tag, lookup);
            stored = tag.contains("Vehicle", 10) ? tag.getCompound("Vehicle").copy() : null;
            yaw = tag.getFloat("Yaw");
            ignored.clear();
            for (var id : tag.getList("Released", 11))
                ignored.add(net.minecraft.nbt.NbtUtils.loadUUID(id));
            candidate = null;
            quiet = 0;
            revision++;
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
            return saveWithoutMetadata(lookup);
        }

        @Override
        public ClientboundBlockEntityDataPacket getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }

    /** Never linked unless Sable is present; the outer class/renderer use vanilla and JOML types only. */
    private static final class Hull {
        static Frame frame(Level level, BlockPos pos) {
            var body = dev.ryanhcode.sable.Sable.HELPER.getContaining(level, pos);
            if (body == null)
                return new Frame(Vec3.atBottomCenterOf(pos.above()), new Quaterniond());
            var top =
                    body.logicalPose()
                            .transformPosition(
                                    new Vector3d(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5));
            return new Frame(
                    new Vec3(top.x, top.y, top.z),
                    new Quaterniond(body.logicalPose().orientation()));
        }

        static void initialize() {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.tick.LevelTickEvent.Post e) -> {
                        if (!(e.getLevel() instanceof ServerLevel level)) return;
                        var container =
                                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(
                                        level);
                        if (container != null)
                            for (var body : container.getAllSubLevels())
                                if (!body.isRemoved())
                                    for (var holder : body.getPlot().getLoadedChunks())
                                        for (var be : holder.getChunk().getBlockEntities().values())
                                            if (be instanceof Dock dock && !dock.isRemoved())
                                                dock.tick();
                    });
        }

        static void sync(Dock dock) {
            var body =
                    dev.ryanhcode.sable.Sable.HELPER.getContaining(
                            dock.getLevel(), dock.getBlockPos());
            if (body instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel ship)
                for (var player : ((ServerLevel) dock.getLevel()).players())
                    if (ship.getTrackingPlayers().contains(player.getUUID()))
                        player.connection.send(dock.getUpdatePacket());
        }
    }

    private AircraftDock() {}
}
