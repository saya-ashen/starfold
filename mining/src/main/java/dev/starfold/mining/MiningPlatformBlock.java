package dev.starfold.mining;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class MiningPlatformBlock extends BaseEntityBlock {
    public static final MapCodec<MiningPlatformBlock> CODEC = simpleCodec(MiningPlatformBlock::new);

    public MiningPlatformBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MiningBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(
                        type, StarfoldMining.PLATFORM_BE.get(), (l, p, s, be) -> be.tick());
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MiningBlockEntity be)
            be.placedBy(placer instanceof Player player ? player.getUUID() : null);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server
                && level.getBlockEntity(pos) instanceof MiningBlockEntity be) {
            server.openMenu(
                    be,
                    buffer ->
                            buffer.writeBlockPos(
                                    dev.starfold.ground.ObserverFrames.get(server).toClient(pos)));
            be.sync();
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())
                && level.getBlockEntity(pos) instanceof MiningBlockEntity be
                && !level.isClientSide) be.dropContents();
        super.onRemove(state, level, pos, next, moving);
    }
}
