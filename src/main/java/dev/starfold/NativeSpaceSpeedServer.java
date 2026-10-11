package dev.starfold;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Hull-local full-command hardware cache. Never uses current redstone/throttle or VACUUM gain. */
public final class NativeSpaceSpeedServer {
    private record Hardware(int[] jets) {}
    private record Reading(Hardware hardware,Direction bow,double mass,NativeSpaceSpeed.Caps caps) {}
    private static final java.util.Map<ServerSubLevel,Reading> cache=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Called after successful chunk writes, including edits made without Level.setBlock. */
    public static void changed(ServerLevel level,BlockPos pos){
        if(dev.ryanhcode.sable.Sable.HELPER.getContaining(level,pos) instanceof ServerSubLevel body)cache.remove(body);
    }
    static NativeSpaceSpeed.Caps caps(ServerSubLevel body){
        var bow=NativeHelmServer.facing(body);double mass=body.getMassTracker().getMass();
        var actors=body.getPlot().getBlockEntityActors();var old=cache.get(body);
        var hardware=old==null||old.mass!=mass?null:old.hardware;
        if(hardware==null){
            int[] jets=new int[Direction.values().length];
            for(var actor:actors)if(actor instanceof NativeThruster.Thruster jet&&!jet.isRemoved())jets[jet.getBlockState().getValue(BlockStateProperties.FACING).getOpposite().ordinal()]++;
            hardware=new Hardware(jets);
        }
        if(old!=null&&hardware==old.hardware&&old.bow==bow&&old.mass==mass)return old.caps;
        var caps=NativeSpaceSpeed.caps(NativeSpaceSpeed.acceleration(hardware.jets[bow.ordinal()]*NativeThruster.THRUST,mass));
        cache.put(body,new Reading(hardware,bow,mass,caps));return caps;
    }
    private NativeSpaceSpeedServer(){}
}
