package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.NativeGroundFlightClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ClientPacketListener.class)
public abstract class NativeGroundSeamTeleportClientMixin {
    @WrapMethod(method="handleMovePlayer")
    private void starfold$nativeSeamVelocity(ClientboundPlayerPositionPacket packet,Operation<Void> original){if(!Minecraft.getInstance().isSameThread()){original.call(packet);return;}var player=Minecraft.getInstance().player;Vec3 velocity=player==null?Vec3.ZERO:player.getDeltaMovement();original.call(packet);NativeGroundFlightClient.teleported(velocity);}
}
