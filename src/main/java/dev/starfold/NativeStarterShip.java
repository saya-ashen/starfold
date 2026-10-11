package dev.starfold;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Explicit preset opt-in. All placement and assembly happen before the first player arrives. */
public final class NativeStarterShip {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("starfold-starter");
    static final String FILE="starfold_starter_ship_v1";
    public static final String DEPENDENCY_MESSAGE="Experience requires Create and Simulated (Create Aeronautics). Install both, or choose native_universe.";
    static final ResourceLocation WEAPON=ResourceLocation.parse("starfold:starter_ship_weapon");
    static final BlockPos WEAPON_OFFSET=new BlockPos(31,4,8);
    public record Option(ResourceLocation structure) {
        public static final Codec<Option> CODEC=RecordCodecBuilder.create(i->i.group(ResourceLocation.CODEC.fieldOf("structure").forGetter(Option::structure)).apply(i,Option::new));
    }
    static final class State extends SavedData {
        private boolean reserved,done;private BlockPos spawn=BlockPos.ZERO;private UUID hull;private BlockPos padCentre=BlockPos.ZERO;private double padRadius;
        boolean fresh(){return !reserved;}boolean complete(){return done;}BlockPos spawn(){return spawn;}
        void reserve(BlockPos at){if(reserved)throw new IllegalStateException("Starter ship already reserved");reserved=true;spawn=at.immutable();setDirty();}
        void finish(UUID id){if(!reserved||done)throw new IllegalStateException("Starter ship completion out of order");hull=id;done=true;setDirty();}
        static State load(CompoundTag tag,HolderLookup.Provider lookup){var s=new State();s.reserved=tag.getBoolean("reserved");s.done=tag.getBoolean("done");s.spawn=BlockPos.of(tag.getLong("spawn"));s.padCentre=BlockPos.of(tag.getLong("padCentre"));s.padRadius=tag.getDouble("padRadius");if(tag.hasUUID("hull"))s.hull=tag.getUUID("hull");if(s.done&&(!s.reserved||s.hull==null))throw new IllegalStateException("Invalid starter ship save");return s;}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putBoolean("reserved",reserved);tag.putBoolean("done",done);tag.putLong("spawn",spawn.asLong());tag.putLong("padCentre",padCentre.asLong());tag.putDouble("padRadius",padRadius);if(hull!=null)tag.putUUID("hull",hull);return tag;}
    }
    static State state(ServerLevel level){return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(()->{
        if(((dev.starfold.mixin.PlanetSpaceDataAccess)level.getDataStorage()).starfold$spaceDataFile(FILE).exists())throw new IllegalStateException("Starter ship save unreadable; refusing duplicate");return new State();},State::load,null),FILE);}
    public static void initialize(){
        // Stop cleanly before assembly, including dedicated servers (this event is not cancellable).
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerAboutToStartEvent event)->{
            var dimensions=event.getServer().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM);
            if(missingDependencies()&&dimensions.stream().anyMatch(stem->stem.generator() instanceof NativeSystemChunkGenerator system&&system.starterShip().isPresent())){
                LOG.error(DEPENDENCY_MESSAGE);event.getServer().halt(false);
            }
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event)->{
            if(missingDependencies())return;
            var level=event.getServer().getLevel(NativeGroundServer.PLANET);
            if(level!=null&&level.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator system)system.starterShip().ifPresent(option->create(level,system,option));
        });
    }
    /** Only the fresh home entry changes; saved travel, relog and bed respawn still use their own locations. */
    static BentStripGeometry.Point home(ServerLevel level,int face){
        if(level.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator system&&system.starterShip().isPresent()){
            var saved=state(level);if(!saved.complete())throw new IllegalStateException("Starter ship creation incomplete; inspect server log, not regenerating");
            var p=saved.spawn();return new BentStripGeometry.Point(p.getX()+.5,p.getY(),p.getZ()+.5);
        }
        return NativePlanetSpawn.site(level,0,face);
    }
    private static NativeStarterSite.Column column(ServerLevel level,int x,int z){
        var chunk=level.getChunk(x>>4,z>>4);int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15);boolean water=false,tree=false;
        int y=top;for(;y>level.getMinBuildHeight();y--){var pos=new BlockPos(x,y,z);var s=level.getBlockState(pos);
            water|=!s.getFluidState().isEmpty();boolean wood=s.is(net.minecraft.tags.BlockTags.LOGS)||s.is(net.minecraft.tags.BlockTags.LEAVES);tree|=wood;
            if(!wood&&s.getFluidState().isEmpty()&&s.isSolidRender(level,pos))break;
        }
        return new NativeStarterSite.Column(y,water,tree);
    }
    private static void create(ServerLevel level,NativeSystemChunkGenerator system,Option option){
        var saved=state(level);if(!saved.fresh()){LOG.info("STARTER reuse complete={} hull={} spawn={}",saved.complete(),saved.hull,saved.spawn());return;}
        requireDependencies(net.neoforged.fml.ModList.get().isLoaded("create"),net.neoforged.fml.ModList.get().isLoaded("simulated"));
        var template=level.getStructureManager().get(option.structure()).orElseThrow(()->new IllegalStateException("Missing starter structure "+option.structure()));
        if(option.structure().equals(ResourceLocation.parse("starfold:starter_ship"))&&weaponEnabled(net.neoforged.fml.ModList.get().isLoaded("sentrymechanicalarm"),net.neoforged.fml.ModList.get().isLoaded("tacz"))){
            var weapon=level.getStructureManager().get(WEAPON).orElseThrow(()->new IllegalStateException("Missing optional starter weapon structure"));
            var combined=mergeWeapon(template.save(new CompoundTag()),weapon.save(new CompoundTag()));
            // Never mutate the structure manager's cached unarmed template.
            template=new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
            template.load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),combined);
        }
        var size=template.getSize();if(size.getX()<1||size.getY()<1||size.getZ()<1||size.getX()>96||size.getY()>48||size.getZ()>96)throw new IllegalStateException("Starter structure size outside 1..96 x 1..48 x 1..96");
        var tag=template.save(new CompoundTag());var palette=tag.getList("palette",10);var entries=tag.getList("blocks",10);
        var relative=new ArrayList<BlockPos>();BlockPos door=null;Direction outward=null;
        for(int i=0;i<entries.size();i++){
            var b=entries.getCompound(i);var p=b.getList("pos",3);var at=new BlockPos(p.getInt(0),p.getInt(1),p.getInt(2));var s=palette.getCompound(b.getInt("state"));String name=s.getString("Name");
            if(name.equals("minecraft:air")||name.equals("minecraft:cave_air")||name.equals("minecraft:void_air"))continue;
            relative.add(at);
            if(door==null&&name.equals("starfold:airstair")){door=at;outward=Direction.byName(s.getCompound("Properties").getString("facing"));}
        }
        if(relative.isEmpty()||door==null||outward==null||outward.getAxis()==Direction.Axis.Y)throw new IllegalStateException("Starter resource needs blocks and an outward airstair");
        int face=NativeGroundDaylight.homeFace(NativeGroundClock.get(level).clock.model,NativePlanetShape.of(0));
        var home=NativePlanetSpawn.site(level,0,face);
        double radius=NativeStarterSite.landingRadius(size.getX(),size.getZ());
        int faceStart=face*4096,planetSize=system.planet(0).terrain().shape().size();
        long started=System.nanoTime();var terrain=system.planet(0).terrain();var surfaceFace=CubeSurfaceTopology.Face.values()[face];
        var cache=new HashMap<Long,NativeStarterSite.Column>();
        // Cheap surface-only baseline: never synthesizes underground columns or generates chunks.
        NativeStarterSite.Heights cheap=(x,z)->cache.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x,z),key->{
            if(x<faceStart+16||x>=faceStart+planetSize-16||z<16||z>=planetSize-16)return new NativeStarterSite.Column(10000,true,true);
            if(level.getChunkSource().getChunkNow(x>>4,z>>4)!=null)return column(level,x,z);
            int u=x-faceStart,ground=terrain.groundY(surfaceFace,u,z);boolean tree=false;
            if(terrain.ecology()!=null){var decoration=terrain.ecology().decoration(surfaceFace,u,z);for(int y=ground+1;y<=ground+10;y++){var s=decoration.above(y);tree|=s.is(net.minecraft.tags.BlockTags.LOGS)||s.is(net.minecraft.tags.BlockTags.LEAVES);}}
            return new NativeStarterSite.Column(ground,terrain.surfaceTop(ground)>ground,tree);
        });
        var ranked=NativeStarterSite.rank((int)home.x(),(int)home.z(),radius,320,cheap);
        var actual=new HashMap<Long,NativeStarterSite.Column>();NativeStarterSite.Circle site=null;int checked=0;
        for(var candidate:ranked){
            if(candidate.x()-radius-8<faceStart+16||candidate.x()+radius+8>=faceStart+planetSize-16||candidate.z()-radius-8<16||candidate.z()+radius+8>=planetSize-16)continue;
            // Verify all baseline-qualified alternatives; if there are none, verify the best fallback once.
            var verified=NativeStarterSite.inspect(candidate.x(),candidate.z(),radius,(x,z)->actual.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x,z),key->column(level,x,z)));checked++;
            verified=new NativeStarterSite.Circle(verified.x(),verified.y(),verified.z(),verified.wet(),verified.nearWater(),verified.trees(),verified.relief(),candidate.distance());
            if(site==null||NativeStarterSite.order().compare(verified,site)<0)site=verified;
            if(verified.qualified()||!candidate.qualified())break;
        }
        if(site==null||site.y()+size.getY()+5>=level.getMaxBuildHeight())throw new IllegalStateException("No bounded starter site inside face/build boundary");
        if(!site.qualified())LOG.warn("STARTER ISLAND/FALLBACK: no verified dry, treeless, level landing circle with water buffer; site={}",site);
        long fullChunks=actual.keySet().stream().map(k->net.minecraft.world.level.ChunkPos.asLong(net.minecraft.world.level.ChunkPos.getX(k)>>4,net.minecraft.world.level.ChunkPos.getZ(k)>>4)).distinct().count();
        LOG.info("STARTER SITE original={} centre={} radius={} distance={} cheapColumns={} fullColumns={} fullChunks={} candidatesChecked={} elapsedMs={}",home,site,radius,Math.hypot(site.x()-home.x()+.5,site.z()-home.z()+.5),cache.size(),actual.size(),fullChunks,checked,(System.nanoTime()-started)/1000000);
        // Centre the hull's bounding footprint, independent of its mass/physics pivot.
        var origin=new BlockPos(site.x()-size.getX()/2,site.y()+1,site.z()-size.getZ()/2);
        // Twelve blocks diagonally off the bow quarter; look back along the full hull.
        var spawn=origin.offset(-2,0,-2).atY(site.y()+1);
        saved.padCentre=new BlockPos(site.x(),site.y(),site.z());saved.padRadius=radius;
        saved.reserve(spawn);level.getDataStorage().save(); // fail closed on interrupted placement; never duplicate a surviving hull
        var padMask=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        int n=(int)Math.ceil(radius+3),skippedDeep=0;
        for(int dx=-n;dx<=n;dx++)for(int dz=-n;dz<=n;dz++){
            double distance=Math.hypot(dx,dz);if(distance>radius+3)continue;
            int x=site.x()+dx,z=site.z()+dz;var c=column(level,x,z);int ground=c.ground();
            int waterDepth=c.water()?Math.max(0,level.getChunk(x>>4,z>>4).getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15)-ground):0;
            if(!NativeStarterSite.foundationAllowed(waterDepth)){skippedDeep++;continue;}
            boolean main=NativeStarterSite.padCell(dx,dz,radius);
            int top=main?site.y():site.y()-(int)Math.ceil(distance-radius);
            if(!main&&ground>=top)continue;
            for(int y=ground+1;y<top;y++)level.setBlock(new BlockPos(x,y,z),Blocks.ANDESITE.defaultBlockState(),2);
            boolean cross=(Math.abs(dx)<=1||Math.abs(dz)<=1)&&distance<radius-2;
            boolean cardinal=(Math.abs(dx)<=2||Math.abs(dz)<=2)&&distance>=radius-3;
            var material=(cross||cardinal?Blocks.GLASS:distance>radius-1.5&&main?Blocks.STONE_BRICKS:Blocks.POLISHED_DEEPSLATE).defaultBlockState();
            if(cross||cardinal){var lamp=new BlockPos(x,top-1,z);level.setBlock(lamp,Blocks.SEA_LANTERN.defaultBlockState(),2);padMask.put(lamp,Blocks.SEA_LANTERN.defaultBlockState());}
            var at=new BlockPos(x,top,z);level.setBlock(at,material,2);padMask.put(at,material);
            int ceiling=Math.max(top+size.getY()+5,level.getChunk(x>>4,z>>4).getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15));
            for(int y=top+1;y<=ceiling;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
        }
        LOG.info("STARTER PAD radius={} wetBefore={} bufferWater={} trees={} relief={} skippedDeepWater={}",radius,site.wet(),site.nearWater(),site.trees(),site.relief(),skippedDeep);
        if(!template.placeInWorld(level,origin,origin,new StructurePlaceSettings().setIgnoreEntities(true),level.random,2))throw new IllegalStateException("Starter structure placement failed");
        var mask=relative.stream().map(origin::offset).toList();boolean lectern=false,chest=false;
        for(var p:mask){
            if(level.getBlockState(p).isAir())throw new IllegalStateException("Starter palette block missing at "+p);
            var entity=level.getBlockEntity(p);
            if(entity instanceof LecternBlockEntity l){l.setBook(NativeStarterBooks.book("zh_cn"));level.setBlock(p,level.getBlockState(p).setValue(BlockStateProperties.HAS_BOOK,true),2);l.setChanged();lectern=true;}
            if(entity instanceof ChestBlockEntity c&&p.equals(origin.offset(29,5,9))){c.setItem(4,NativeStarterBooks.book("zh_cn"));c.setItem(5,NativeStarterBooks.book("en_us"));if(net.neoforged.fml.ModList.get().isLoaded("immersive_aircraft")){var aircraft=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("immersive_aircraft:quadrocopter"));if(aircraft!=net.minecraft.world.item.Items.AIR){c.setItem(6,new net.minecraft.world.item.ItemStack(aircraft));c.setItem(7,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL,64));}}c.setChanged();chest=true;}
        }
        if(!lectern||!chest)throw new IllegalStateException("Starter resource needs cabin lectern and chest");
        LOG.info("STARTER placed structure={} origin={} blocks={} site={} foundation={} spawn={} seed={}",option.structure(),origin,mask.size(),site,site.relief()>0?"levelled circle":"flush circle",spawn,level.getSeed());
        var bounds=new BoundingBox3i().set(origin.getX()-1,origin.getY()-1,origin.getZ()-1,origin.getX()+size.getX(),origin.getY()+size.getY(),origin.getZ()+size.getZ());
        var hull=SubLevelAssemblyHelper.assembleBlocks(level,mask.getFirst(),mask,bounds);
        if(hull==null||hull.getMassTracker().isInvalid())throw new IllegalStateException("Starter hull assembly failed");
        int census=0;for(var holder:hull.getPlot().getLoadedChunks()){var chunk=holder.getChunk();for(var section:chunk.getSections())for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++)if(!section.getBlockState(x,y,z).isAir())census++;}
        if(census!=mask.size())throw new IllegalStateException("Starter hull census "+census+" != "+mask.size());
        for(var entry:padMask.entrySet())if(!level.getBlockState(entry.getKey()).equals(entry.getValue()))throw new IllegalStateException("Starter assembly changed the pad");
        saved.finish(hull.getUniqueId());level.setDefaultSpawnPos(spawn,-55);level.getDataStorage().save();
        LOG.info("STARTER assembled hull={} census={} expected={} padCaptured=0 spawn={}",hull.getUniqueId(),census,mask.size(),spawn);
    }
    static boolean overPad(ServerLevel level,dev.ryanhcode.sable.sublevel.ServerSubLevel body){
        var saved=state(level);if(!saved.complete()||saved.padRadius<=0)return false;
        var b=body.getPlot().getBoundingBox();var access=NativePlanetAccess.of(level);
        for(double x:new double[]{b.minX(),b.maxX()+1.0})for(double z:new double[]{b.minZ(),b.maxZ()+1.0})for(double y:new double[]{b.minY(),b.maxY()+1.0}){
            var p=body.logicalPose().transformPosition(new org.joml.Vector3d(x,y,z));
            if(!access.inChart(BlockPos.containing(p.x,p.y,p.z)))return false;
            var owner=access.address(BlockPos.containing(p.x,p.y,p.z)).owner();
            if(!NativeStarterSite.padCell(owner.getX()-saved.padCentre.getX(),owner.getZ()-saved.padCentre.getZ(),saved.padRadius))return false;
        }
        return true;
    }
    static BentStripGeometry.Point launchCentre(ServerLevel level,dev.ryanhcode.sable.sublevel.ServerSubLevel body){
        var saved=state(level);if(!overPad(level,body))return null;
        return new BentStripGeometry.Point(saved.padCentre.getX()+.5,saved.padCentre.getY()+1,saved.padCentre.getZ()+.5);
    }
    public static boolean missingDependencies(){return !dependenciesPresent(net.neoforged.fml.ModList.get().isLoaded("create"),net.neoforged.fml.ModList.get().isLoaded("simulated"));}
    static boolean dependenciesPresent(boolean create,boolean simulated){return create&&simulated;}
    static void requireDependencies(boolean create,boolean simulated){if(!dependenciesPresent(create,simulated))throw new IllegalStateException(DEPENDENCY_MESSAGE);}
    static boolean weaponEnabled(boolean sentry,boolean tacz){return sentry&&tacz;}
    /** Pure sparse overlay merge. The complete merged mask is assembled in one operation. */
    static CompoundTag mergeWeapon(CompoundTag hull,CompoundTag weapon){
        var result=hull.copy();var palette=result.getList("palette",10);int start=palette.size();
        for(var state:weapon.getList("palette",10))palette.add(state.copy());
        var blocks=result.getList("blocks",10);var overlay=new LinkedHashMap<BlockPos,CompoundTag>();
        for(var entry:weapon.getList("blocks",10)){
            var block=((CompoundTag)entry).copy();var pos=block.getList("pos",3);
            var at=new BlockPos(pos.getInt(0),pos.getInt(1),pos.getInt(2)).offset(WEAPON_OFFSET);
            var translated=new net.minecraft.nbt.ListTag();for(int n:new int[]{at.getX(),at.getY(),at.getZ()})translated.add(net.minecraft.nbt.IntTag.valueOf(n));
            block.put("pos",translated);block.putInt("state",block.getInt("state")+start);overlay.put(at,block);
        }
        blocks.removeIf(entry->{var p=((CompoundTag)entry).getList("pos",3);return overlay.containsKey(new BlockPos(p.getInt(0),p.getInt(1),p.getInt(2)));});
        // Sparse overlay air, if present, is not an assembly cell.
        overlay.values().stream().filter(block->!palette.getCompound(block.getInt("state")).getString("Name").equals("minecraft:air")).forEach(blocks::add);
        var size=result.getList("size",3);var extra=weapon.getList("size",3);int[] offset={WEAPON_OFFSET.getX(),WEAPON_OFFSET.getY(),WEAPON_OFFSET.getZ()};
        for(int i=0;i<3;i++)size.set(i,net.minecraft.nbt.IntTag.valueOf(Math.max(size.getInt(i),offset[i]+extra.getInt(i))));
        return result;
    }
    static float homeYaw(ServerLevel level){return level.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator system&&system.starterShip().isPresent()?-55:0;}
    private NativeStarterShip(){}
}
