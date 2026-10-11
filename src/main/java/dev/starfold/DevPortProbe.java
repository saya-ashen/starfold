package dev.starfold;

/** Explicit, opt-in render evidence controls. No world or terrain changes. */
public final class DevPortProbe {
    public static boolean hold,legacyTint;
    public static Double sky;
    public static boolean enabled(){return Boolean.getBoolean("starfold.portProbe")&&DevProbe.active();}
    public static void command(String args){
        if(!enabled())throw new IllegalStateException("portProbe not enabled");
        var a=args.split(" ");
        switch(a[0]){
            case "hold" -> hold=Boolean.parseBoolean(a[1]);
            case "legacy" -> {if(NativeGroundClient.planet()!=0)throw new IllegalStateException("home only");legacyTint=Boolean.parseBoolean(a[1]);NativeGroundRenderer.clear();}
            case "audit" -> audit();
            case "sky" -> {
                if(a[1].equals("live"))sky=null;
                else if(a[1].equals("noon")){
                    double best=-2,chosen=0,period=NativeGroundClient.model().period();
                    for(int i=0;i<4096;i++){sky=period*i/4096;double e=NativeGroundClient.sunElevation();if(e>best){best=e;chosen=sky;}}
                    sky=chosen;
                }else sky=Double.parseDouble(a[1]);
                org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE PORT SKY {} elevation {}",sky,NativeGroundClient.sunElevation());
            }
            default -> throw new IllegalArgumentException(args);
        }
    }
    private static void audit(){
        var mc=net.minecraft.client.Minecraft.getInstance();var t=NativeGroundViewClient.terrain();var profile=NativeGroundViewClient.profile();int id=NativeGroundClient.planet(),face=NativeGroundClient.face();
        var view=NativePlanetTint.view(mc.level,mc.level.registryAccess(),id,t,profile.biome());
        var seen=new java.util.HashSet<String>();
        for(int x=64;x<t.shape().size()-64&&seen.size()<3;x+=32)for(int z=64;z<t.shape().size()-64&&seen.size()<3;z+=32){
            var key=NativePlanetTint.biome(t,profile.biome(),face,x,z);if(!seen.add(key.location().toString()))continue;
            var p=NativePlanetTint.position(id,face,x,t.surfaceY(CubeSurfaceTopology.Face.values()[face],x,z),z);
            var oldPos=new net.minecraft.core.BlockPos(face*4096+x,p.getY(),z);
            var chunk=mc.level.getChunkSource().getChunk(oldPos.getX()>>4,oldPos.getZ()>>4,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
            org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE PORT CHUNK pos {} chunk {}",oldPos,chunk==null?"null":chunk.getClass().getName()+" "+chunk.getPos());
            for(var b:java.util.List.of(net.minecraft.world.level.block.Blocks.GRASS_BLOCK,net.minecraft.world.level.block.Blocks.OAK_LEAVES,net.minecraft.world.level.block.Blocks.WATER)){
                var state=b.defaultBlockState();int oldTint,newTint;
                if(b==net.minecraft.world.level.block.Blocks.WATER){oldTint=net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(mc.level,oldPos);newTint=net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(view,p);}
                else{oldTint=mc.getBlockColors().getColor(state,mc.level,oldPos,0);newTint=mc.getBlockColors().getColor(state,view,p,0);}
                org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE PORT TINT planet {} pos {} state {} loaded {} clientBiome {} analyticalBiome {} blendRadius {} oldVanilla {} newExplicit {}",id,p,state,mc.level.hasChunkAt(oldPos),mc.level.getBiome(oldPos).unwrapKey(),key.location(),mc.options.biomeBlendRadius().get(),Integer.toHexString(oldTint&0xffffff),Integer.toHexString(newTint&0xffffff));
            }
        }
    }
    private DevPortProbe(){}
}
