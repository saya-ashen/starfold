package dev.starfold.materials;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Decorative ship sections with baked meshes and stepped collision. */
final class HullShapeBlock extends Block {
    static final MapCodec<HullShapeBlock> ARMOR = simpleCodec(p -> new HullShapeBlock(p, false));
    static final MapCodec<HullShapeBlock> GLASS = simpleCodec(p -> new HullShapeBlock(p, true));
    private static final EnumProperty<Profile> SHAPE = EnumProperty.create("shape", Profile.class);
    private static final int STEPS = 8;
    private final boolean glass;
    private final VoxelShape[] outlines;

    HullShapeBlock(Properties properties, boolean glass) {
        super(properties);
        this.glass = glass;
        registerDefaultState(stateDefinition.any().setValue(SHAPE, Profile.RAMP)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM));
        outlines = new VoxelShape[Profile.values().length * 8];
        for (var state : stateDefinition.getPossibleStates()) outlines[index(state)] = outline(state);
    }

    @Override
    protected MapCodec<HullShapeBlock> codec() {
        return glass ? GLASS : ARMOR;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SHAPE, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.HALF);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var half = context.getClickedFace() == Direction.DOWN
                || (context.getClickedFace() != Direction.UP
                        && context.getClickLocation().y - context.getClickedPos().getY() > .5)
                ? Half.TOP : Half.BOTTOM;
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,
                context.getHorizontalDirection().getOpposite()).setValue(BlockStateProperties.HALF, half);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()
                || !player.getOffhandItem().isEmpty() || !player.mayBuild()) return InteractionResult.PASS;
        if (!level.isClientSide()) level.setBlock(pos, state.cycle(SHAPE), UPDATE_ALL);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING,
                rotation.rotate(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        var facing = mirror.mirror(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        if (state.getValue(SHAPE) == Profile.CORNER) {
            if (mirror != Mirror.NONE) facing = facing.getClockWise();
        }
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return outlines[index(state)];
    }

    private static int index(BlockState state) {
        return state.getValue(SHAPE).ordinal() * 8
                + state.getValue(BlockStateProperties.HORIZONTAL_FACING).get2DDataValue() * 2
                + (state.getValue(BlockStateProperties.HALF) == Half.TOP ? 1 : 0);
    }

    private VoxelShape outline(BlockState state) {
        var result = Shapes.empty();
        var profile = state.getValue(SHAPE);
        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean flipped = state.getValue(BlockStateProperties.HALF) == Half.TOP;
        int columns = profile == Profile.CORNER ? STEPS : 1;
        for (int z = 0; z < STEPS; z++) {
            for (int x = 0; x < columns; x++) {
                double u = 16.0 * (x + .5) / columns;
                double v = 16.0 * (z + .5) / STEPS;
                double height = profile.height(u, v);
                double high = glass ? 2 + height * .875 : height;
                double low = glass ? height * .875 : 0;
                double dx = 8.0 / columns;
                var box = switch (facing) {
                    case EAST -> box(16 - v - 1, flipped ? 16 - high : low, u - dx,
                            16 - v + 1, flipped ? 16 - low : high, u + dx);
                    case SOUTH -> box(16 - u - dx, flipped ? 16 - high : low, 16 - v - 1,
                            16 - u + dx, flipped ? 16 - low : high, 16 - v + 1);
                    case WEST -> box(v - 1, flipped ? 16 - high : low, 16 - u - dx,
                            v + 1, flipped ? 16 - low : high, 16 - u + dx);
                    default -> box(u - dx, flipped ? 16 - high : low, v - 1,
                            u + dx, flipped ? 16 - low : high, v + 1);
                };
                result = Shapes.or(result, box);
            }
        }
        return result.optimize();
    }

    private enum Profile implements StringRepresentable {
        RAMP("ramp"), HALF_RAMP("half_ramp"), HIGH_RAMP("high_ramp"), BEVEL("bevel"), CORNER("corner");

        private final String name;

        Profile(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }

        double height(double x, double z) {
            return switch (this) {
                case RAMP -> z;
                case HALF_RAMP -> z / 2;
                case HIGH_RAMP -> 8 + z / 2;
                case BEVEL -> Math.min(16, 8 + z);
                case CORNER -> Math.min(x, z);
            };
        }
    }
}
