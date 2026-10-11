package dev.starfold.mixin;
import dev.starfold.NativeGroundFlightServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flight metadata owns high-altitude movement, not a second vanilla movement packet stream. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class NativeGroundFlightNetworkMixin {
    @Shadow public ServerPlayer player;
    private boolean starfold$owns(){return player.getServer().isSameThread()&&NativeGroundFlightServer.controlled(player);}
    @Inject(method="handleMovePlayer",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeMove(ServerboundMovePlayerPacket packet,CallbackInfo ci){if(starfold$owns())ci.cancel();}
    @Inject(method="handleUseItemOn",at=@At("HEAD"),cancellable=true)
    private void starfold$nativePlace(ServerboundUseItemOnPacket packet,CallbackInfo ci){if(starfold$owns())ci.cancel();}
    @Inject(method="handlePlayerAction",at=@At("HEAD"),cancellable=true)
    private void starfold$nativeBreak(ServerboundPlayerActionPacket packet,CallbackInfo ci){if(!starfold$owns())return;switch(packet.getAction()){case START_DESTROY_BLOCK,STOP_DESTROY_BLOCK,ABORT_DESTROY_BLOCK->ci.cancel();default->{}}}
}
