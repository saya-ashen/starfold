package dev.starfold;

import net.minecraft.core.Direction;
import org.joml.Vector3dc;

/** The same body-axis selector on the physics and rendering sides. No world or block-state writes. */
public final class NativeHelmCommand {
    public static final int FORWARD=1,BACK=2,LEFT=4,RIGHT=8,UP=16,DOWN=32,SPRINT=64,ALL=127,LEASE=5;
    public static boolean fresh(long sent,long now){return sent>=0&&now>=sent&&now-sent<=LEASE;}
    public static int effective(int redstone,boolean commanded){return Math.max(redstone,commanded?15:0);}
    public static boolean boosted(int bits,boolean booster){return booster&&(bits&SPRINT)!=0;}
    /** Positive up moment yaws left for every horizontal bow. Free-flight A is positive frame roll. */
    public static int roll(int bits){return ((bits&LEFT)!=0?1:0)-((bits&RIGHT)!=0?1:0);}
    public static boolean fires(int bits,Direction forward,Direction nozzle,Vector3dc arm,boolean free){
        var push=nozzle.getOpposite();double along=push.getStepX()*forward.getStepX()+push.getStepZ()*forward.getStepZ();
        if((bits&FORWARD)!=0&&along>.5||(bits&BACK)!=0&&along<-.5||(bits&UP)!=0&&push==Direction.UP||(bits&DOWN)!=0&&push==Direction.DOWN)return true;
        double side=-forward.getStepZ()*push.getStepX()+forward.getStepX()*push.getStepZ(),torque=arm.z()*push.getStepX()-arm.x()*push.getStepZ();
        return !free&&Math.abs(side)>.5&&((bits&LEFT)!=0&&torque>1e-6||(bits&RIGHT)!=0&&torque< -1e-6);
    }
    /** Runtime-only ownership: one pilot per hull/controller, one controller per pilot. Never serialized. */
    static final class Pilots<B,P> {
        private final java.util.Map<B,P> owners=new java.util.HashMap<>();
        boolean take(B body,P pilot){var owner=owners.get(body);if(owner!=null&&!owner.equals(pilot))return false;owners.entrySet().removeIf(e->e.getValue().equals(pilot));owners.put(body,pilot);return true;}
        P owner(B body){return owners.get(body);}
        void release(B body){owners.remove(body);}
        void clear(){owners.clear();}
    }
    private NativeHelmCommand(){}
}
