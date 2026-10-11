package dev.starfold.mixin;
import dev.starfold.NativeImageStream;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native charts are streamed as images (halo included) by NativeImageStream, never as raw storage chunks. */
@Mixin(PlayerChunkSender.class)
public abstract class NativeGroundChunkSenderMixin {
    @Shadow @Final private LongSet pendingChunks;
    @Inject(method="sendNextChunks",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeImages(ServerPlayer player,CallbackInfo ci){if(NativeImageStream.active(player)){pendingChunks.clear();ci.cancel();}}
    @Inject(method="dropChunk",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeDrop(ServerPlayer player,ChunkPos pos,CallbackInfo ci){if(NativeImageStream.active(player)){pendingChunks.remove(pos.toLong());ci.cancel();}}
}
