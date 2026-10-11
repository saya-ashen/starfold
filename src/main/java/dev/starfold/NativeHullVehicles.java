package dev.starfold;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.packet_mixin.PacketActuallyInSubLevelExtension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.phys.Vec3;

/** Client side of a vehicle parked on a hull: Sable's own pin for what stands on a hull, given to boats and aircraft too. */
public final class NativeHullVehicles {
    /** Boats and what mods build on the vehicle class. Minecarts have Sable's own rail handling; nothing piloted from here is pinned. */
    static boolean rides(Entity entity){return entity instanceof VehicleEntity&&!(entity instanceof AbstractMinecart)&&!entity.isControlledByLocalInstance();}
    /** A packet has placed an entity: one the server reports in a hull's own coordinates, while it only stands on that hull, keeps them. */
    public static void placed(Entity entity,double x,double y,double z,Object packet){
        if(!net.neoforged.fml.ModList.get().isLoaded("sable")||!rides(entity))return;
        if(packet instanceof PacketActuallyInSubLevelExtension inside&&inside.sable$isActuallyInSubLevel())return;
        if(Sable.HELPER.getContaining(entity.level(),x,z)!=null)((EntityStickExtension)entity).sable$setPlotPosition(new Vec3(x,y,z));
    }
    /** Taking the controls frees it: no packet moves a vehicle its own client flies, so nothing else would. */
    public static void initialize(){
        if(!net.neoforged.fml.ModList.get().isLoaded("sable"))return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityMountEvent event)->{if(event.getLevel().isClientSide()&&event.getEntityBeingMounted() instanceof VehicleEntity vehicle)((EntityStickExtension)vehicle).sable$setPlotPosition(null);});
    }
    private static final java.util.Map<Entity,dev.ryanhcode.sable.sublevel.SubLevel> PARKED=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Sable says a vehicle stands on a hull only in the ticks it lands on it again; in the others the server reports a
     *  level position and the client lets go, so a parked aircraft was tugged back to its place every few ticks. While the
     *  deck is still under it, it stands on the hull it last stood on. */
    public static void initializeServer(){
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.EntityTickEvent.Post event)->{
            var entity=event.getEntity();if(entity.level().isClientSide()||!(entity instanceof VehicleEntity)||entity instanceof AbstractMinecart)return;
            var standing=Sable.HELPER.getTrackingSubLevel(entity);if(standing!=null){PARKED.put(entity,standing);return;}
            var last=PARKED.get(entity);if(last==null)return;
            if(last.isRemoved()||Sable.HELPER.getContaining(entity)!=null||!decked(entity,last)){PARKED.remove(entity);return;}
            ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension)entity).sable$setTrackingSubLevel(last);
        });
    }
    /** Whether a hull's block lies within half a block under the middle of an entity or under one of its four sides. */
    static boolean decked(Entity entity,dev.ryanhcode.sable.sublevel.SubLevel body){
        double reach=Math.max(.25,entity.getBbWidth()*.4);
        for(double[] d:new double[][]{{0,0},{reach,0},{-reach,0},{0,reach},{0,-reach}}){
            var local=body.logicalPose().transformPositionInverse(new org.joml.Vector3d(entity.getX()+d[0],entity.getY()-.25,entity.getZ()+d[1]));var at=net.minecraft.core.BlockPos.containing(local.x,local.y,local.z);
            if(!entity.level().getBlockState(at).getCollisionShape(entity.level(),at).isEmpty())return true;
        }return false;
    }
    private NativeHullVehicles(){}
}
