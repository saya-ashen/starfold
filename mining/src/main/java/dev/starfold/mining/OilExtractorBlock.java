package dev.starfold.mining;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidUtil;

public final class OilExtractorBlock extends BaseEntityBlock {
    public static final MapCodec<OilExtractorBlock> CODEC = simpleCodec(OilExtractorBlock::new);

    public OilExtractorBlock(Properties properties) {
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
        return new OilExtractorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(type, StarfoldMining.OIL_BE.get(), (l, p, s, be) -> be.tick());
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof OilExtractorBlockEntity be)
            be.placedBy(placer instanceof Player player ? player.getUUID() : null);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (stack.is(Items.BUCKET)) {
            if (!level.isClientSide
                    && level.getBlockEntity(pos) instanceof OilExtractorBlockEntity be)
                FluidUtil.interactWithFluidHandler(player, hand, be.fluidOutlet());
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Allow the console with a held item as well; filled buckets cannot inject fluid.
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server
                && level.getBlockEntity(pos) instanceof OilExtractorBlockEntity be)
            server.openMenu(
                    be,
                    buffer ->
                            buffer.writeBlockPos(
                                    dev.starfold.ground.ObserverFrames.get(server).toClient(pos)));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())
                && !level.isClientSide
                && level.getBlockEntity(pos) instanceof OilExtractorBlockEntity be)
            be.dropContents();
        super.onRemove(state, level, pos, next, moving);
    }
}
