package dev.starfold;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/** Thread-confined fit query, never consulted by movement or terrain collision. */
public final class NativeRiderPoseContext {
    public record Fit(SubLevel carrying,boolean currentPose,boolean standingOrCrouching){}
    public static final ThreadLocal<Fit> FITTING=new ThreadLocal<>();
    /** Read-only probe replay of dev.68 fit policy; always restored by the caller. */
    public static final ThreadLocal<Boolean> BASELINE=new ThreadLocal<>();
    public static Fit fit(Player player,Pose pose){
        boolean baseline=Boolean.TRUE.equals(BASELINE.get());
        return new Fit(Sable.HELPER.getTrackingSubLevel(player),player.level().isClientSide&&(!baseline||Client.live(player)),!baseline&&(pose==Pose.STANDING||pose==Pose.CROUCHING));
    }
    public static boolean liveHelm(Player player){
        if(!player.isAlive()||player.isSpectator())return false;
        if(player instanceof ServerPlayer server)return NativeHelmServer.poseLease(server);
        return player.level().isClientSide&&Client.live(player);
    }
    private static final class Client {
        static boolean live(Player player){
            if(player!=net.minecraft.client.Minecraft.getInstance().player)return false;
            var body=NativeHelmClient.body();return body!=null&&NativeHelmInputClient.state(body.getUniqueId())!=null;
        }
    }
    private NativeRiderPoseContext(){}
}
