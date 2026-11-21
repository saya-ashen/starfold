package dev.starfold.mining;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Protection identity only. ServerPlayer's constructor must not search/load spawn terrain.
 * The caller positions this actor at its already-loaded machine before invoking any hook. */
final class MiningActor extends FakePlayer {
    MiningActor(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public BlockPos adjustSpawnLocation(ServerLevel level, BlockPos spawn) {
        return spawn;
    }
}
