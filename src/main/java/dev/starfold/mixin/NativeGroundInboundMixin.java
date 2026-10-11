package dev.starfold.mixin;
import dev.starfold.NativePacketMap;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Handlers run twice (network thread, then rescheduled on the server thread): map once, on the server thread. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class NativeGroundInboundMixin {
    @Shadow public ServerPlayer player;
    @Unique private boolean starfold$server(){return player.getServer().isSameThread();}
    @ModifyVariable(method="handleMovePlayer",at=@At("HEAD"),argsOnly=true)
    private ServerboundMovePlayerPacket starfold$nativeMoveFrame(ServerboundMovePlayerPacket packet){return starfold$server()?(ServerboundMovePlayerPacket)NativePacketMap.inbound(player,packet):packet;}
    @ModifyVariable(method="handleMoveVehicle",at=@At("HEAD"),argsOnly=true)
    private ServerboundMoveVehiclePacket starfold$nativeVehicleFrame(ServerboundMoveVehiclePacket packet){return starfold$server()?(ServerboundMoveVehiclePacket)NativePacketMap.inbound(player,packet):packet;}
    @ModifyVariable(method="handleUseItemOn",at=@At("HEAD"),argsOnly=true)
    private ServerboundUseItemOnPacket starfold$nativeUseOnFrame(ServerboundUseItemOnPacket packet){return starfold$server()?(ServerboundUseItemOnPacket)NativePacketMap.inbound(player,packet):packet;}
    @ModifyVariable(method="handlePlayerAction",at=@At("HEAD"),argsOnly=true)
    private ServerboundPlayerActionPacket starfold$nativeActionFrame(ServerboundPlayerActionPacket packet){return starfold$server()?(ServerboundPlayerActionPacket)NativePacketMap.inbound(player,packet):packet;}
    @ModifyVariable(method="handleUseItem",at=@At("HEAD"),argsOnly=true)
    private ServerboundUseItemPacket starfold$nativeUseFrame(ServerboundUseItemPacket packet){return starfold$server()?(ServerboundUseItemPacket)NativePacketMap.inbound(player,packet):packet;}
    @ModifyVariable(method="handleSignUpdate",at=@At("HEAD"),argsOnly=true)
    private ServerboundSignUpdatePacket starfold$nativeSignFrame(ServerboundSignUpdatePacket packet){return starfold$server()?(ServerboundSignUpdatePacket)NativePacketMap.inbound(player,packet):packet;}
}
