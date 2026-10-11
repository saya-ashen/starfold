package dev.starfold;

/** Session-local confirmation, using server ticks (three seconds at the normal 20 TPS). */
final class NativeHelmExit {
    private long warned=Long.MIN_VALUE;
    boolean leave(net.minecraft.world.level.GameType mode,boolean space,long now){return leave(mode,space,true,now);}
    boolean leave(net.minecraft.world.level.GameType mode,boolean space,boolean landedWithStair,long now){
        boolean survivalLike=mode==net.minecraft.world.level.GameType.SURVIVAL||mode==net.minecraft.world.level.GameType.ADVENTURE;
        if(!survivalLike||(!space&&landedWithStair)){warned=Long.MIN_VALUE;return true;}
        if(warned!=Long.MIN_VALUE&&now>=warned&&now-warned<60){warned=Long.MIN_VALUE;return true;}
        warned=now;return false;
    }
}
