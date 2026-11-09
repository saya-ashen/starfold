package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.PlanetPlayers;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The level a player joins is chosen here, before the login packet names it to the client. */
@Mixin(PlayerList.class)
public abstract class PlanetJoinMixin {
    @WrapOperation(
            method = "placeNewPlayer",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel starfold$joinPlanet(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            Operation<ServerLevel> original,
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie cookie) {
        var level = original.call(server, dimension);
        return level == null ? null : PlanetPlayers.join(connection, player, level);
    }
}
