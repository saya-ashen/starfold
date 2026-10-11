package dev.starfold;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class NativeWeatherClient {
    private static boolean silent;
    public static boolean absent(Level level){var mc=Minecraft.getInstance();if(level!=mc.level||mc.player==null||!NativeGroundServer.is(level))return false;if(NativeSpaceClient.present())return true;var p=NativeGroundClient.storage(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ());return NativeWeather.absent(level,BlockPos.containing(p.x(),p.y(),p.z()));}
    public static void initialize(){
        // Cut off sounds begun below the air, too; queued sounds still pass the play-time gate below.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event)->{var mc=Minecraft.getInstance();boolean now=absent(mc.level);if(now&&!silent)for(String id:new String[]{"weather.rain","weather.rain.above","entity.lightning_bolt.thunder","entity.lightning_bolt.impact"})mc.getSoundManager().stop(net.minecraft.resources.ResourceLocation.withDefaultNamespace(id),null);silent=now;});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent event)->{if(Boolean.getBoolean("starfold.carryProbe")&&(event.getName().contains("thunder")||event.getName().contains("weather")))org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE WEATHER PLAYED {} wall {}",event.getName(),System.currentTimeMillis());});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.sound.PlaySoundEvent event)->{var sound=event.getSound();if(sound==null)return;var id=sound.getLocation();if(id.getNamespace().equals("minecraft")&&(id.getPath().startsWith("weather.")||id.getPath().startsWith("entity.lightning_bolt."))){boolean mute=absent(Minecraft.getInstance().level);if(mute)event.setSound(null);if(Boolean.getBoolean("starfold.carryProbe"))org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE WEATHER SOUND {} muted {} wall {}",id,mute,System.currentTimeMillis());}});
    }
    private NativeWeatherClient(){}
}
