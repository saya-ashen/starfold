package dev.starfold.worldgen.plan;

import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.worldgen.surface.Scatter;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Server-only spawn instructions; mob initialization needs the real generation region. */
public final class StructureEntities {
    public record Spawn(CompoundTag nbt, boolean finalizeSpawn, long seed, Rotation rotation, Mirror mirror) {
        public Spawn { nbt=nbt.copy(); }
        @Override public CompoundTag nbt() { return nbt.copy(); }
        static Spawn load(CompoundTag tag) {
            return new Spawn(tag.getCompound("nbt"),tag.getBoolean("finalize"),tag.getLong("seed"),Rotation.valueOf(tag.getString("rotation")),Mirror.valueOf(tag.getString("mirror")));
        }
        public CompoundTag save() {
            var tag=new CompoundTag();tag.put("nbt",nbt());tag.putBoolean("finalize",finalizeSpawn);tag.putLong("seed",seed);
            tag.putString("rotation",rotation.name());tag.putString("mirror",mirror.name());return tag;
        }
    }
    public record Projected(Spawn spawn, CompoundTag nbt, int turns) {}
    private record Capture(List<Spawn> spawns,long seed) {}
    private static final ThreadLocal<Capture> CAPTURE=new ThreadLocal<>();
    private StructureEntities() {}
    static void begin(List<Spawn> spawns,long seed) { CAPTURE.set(new Capture(spawns,seed)); }
    static void end() { CAPTURE.remove(); }
    public static ListTag position(double x,double y,double z) {
        var list=new ListTag();list.add(DoubleTag.valueOf(x));list.add(DoubleTag.valueOf(y));list.add(DoubleTag.valueOf(z));return list;
    }
    static CompoundTag entity(String type,Vec3 pos,boolean persistent) {
        var tag=new CompoundTag();tag.putString("id",type);tag.put("Pos",position(pos.x,pos.y,pos.z));
        if(persistent)tag.putBoolean("PersistenceRequired",true);return tag;
    }
    static void record(CompoundTag nbt,boolean finalizeSpawn,Rotation rotation,Mirror mirror) {
        var capture=Objects.requireNonNull(CAPTURE.get());
        if(capture.spawns.size()>=4096)throw new IllegalArgumentException("Structure entity expansion limit");
        long seed=Scatter.mix(capture.seed+capture.spawns.size());
        var copy=nbt.copy();normalize(copy,seed,0,new int[]{4096});
        capture.spawns.add(new Spawn(copy,finalizeSpawn,seed,rotation,mirror));
    }
    private static void normalize(CompoundTag tag,long seed,int depth,int[] remaining) {
        if(--remaining[0]<0 || depth>16 || !BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(tag.getString("id"))))
            throw new IllegalArgumentException("Structure entity type/depth");
        var pos=tag.getList("Pos",Tag.TAG_DOUBLE);
        if(pos.size()!=3 || !Double.isFinite(pos.getDouble(0)) || !Double.isFinite(pos.getDouble(1)) || !Double.isFinite(pos.getDouble(2)))
            throw new IllegalArgumentException("Structure entity position");
        attached(tag);
        tag.putUUID("UUID",new UUID(Scatter.mix(seed),Scatter.mix(~seed)));
        var passengers=tag.getList("Passengers",Tag.TAG_COMPOUND);
        if(passengers.size()>64)throw new IllegalArgumentException("Structure passenger count");
        for(int i=0;i<passengers.size();i++)normalize(passengers.getCompound(i),Scatter.mix(seed+i),depth+1,remaining);
    }
    /** Called at vanilla's entity-processing boundary, before it requires ServerLevel. */
    public static boolean captureTemplate(StructureTemplate template,ServerLevelAccessor level,BlockPos origin,
                                          StructurePlaceSettings settings,List<StructureTemplate.StructureEntityInfo> entities) {
        if(CAPTURE.get()==null)return false;
        for(var info:StructureTemplate.processEntityInfos(template,level,origin,settings,entities)) {
            if(settings.getBoundingBox()!=null && !settings.getBoundingBox().isInside(info.blockPos))continue;
            var tag=info.nbt.copy();tag.put("Pos",position(info.pos.x,info.pos.y,info.pos.z));
            captureAnchors(tag,info.blockPos,info.pos,origin,settings);
            record(tag,settings.shouldFinalizeEntities(),settings.getRotation(),settings.getMirror());
        }
        return true;
    }
    private static boolean attached(CompoundTag tag) {
        boolean known=switch(tag.getString("id")) {
            case "minecraft:painting","minecraft:item_frame","minecraft:glow_item_frame","minecraft:leash_knot" -> true;
            default -> false;
        };
        if(!known && (tag.contains("TileX") || tag.contains("TileY") || tag.contains("TileZ")))
            throw new IllegalArgumentException("Unsupported structure block-attached entity geometry: "+tag.getString("id"));
        return known;
    }
    private static BlockPos anchor(CompoundTag tag) { return new BlockPos(tag.getInt("TileX"),tag.getInt("TileY"),tag.getInt("TileZ")); }
    private static void anchor(CompoundTag tag,BlockPos pos) {
        tag.putInt("TileX",pos.getX());tag.putInt("TileY",pos.getY());tag.putInt("TileZ",pos.getZ());
    }
    private static void captureAnchors(CompoundTag tag,BlockPos blockPos,Vec3 pos,BlockPos origin,StructurePlaceSettings settings) {
        tag.put("Pos",position(pos.x,pos.y,pos.z));
        if(attached(tag))anchor(tag,blockPos);
        for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND)) {
            var passenger=(CompoundTag)child;
            var passengerAnchor=attached(passenger) ? StructureTemplate.calculateRelativePosition(settings,anchor(passenger)).offset(origin) : blockPos;
            captureAnchors(passenger,passengerAnchor,pos,origin,settings);
        }
    }
    private static void namespace(CompoundTag tag,int planet) {
        var uuid=tag.getUUID("UUID");long salt=Scatter.mix(planet);
        tag.putUUID("UUID",new UUID(Scatter.mix(uuid.getMostSignificantBits()^salt),Scatter.mix(uuid.getLeastSignificantBits()^salt)));
        for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))namespace((CompoundTag)child,planet);
    }
    public static ListTag save(List<StructureSandbox.Result> results) {
        var list=new ListTag();for(var result:results)for(var spawn:result.spawns())list.add(spawn.save());return list;
    }
    public static ChunkPos chunk(Projected projected) {
        var pos=projected.nbt.getList("Pos",Tag.TAG_DOUBLE);
        return new ChunkPos(BlockPos.containing(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2)));
    }
    public static Projected project(PlanetPlan.Site site,Spawn spawn,int size,int planet,RegistryAccess registries) {
        var tag=spawn.nbt();
        lower(tag,site.effects().burial());
        int turns=project(tag,site.face(),spawn,size,planet,registries);
        namespace(tag,planet);return new Projected(spawn,tag,turns);
    }
    private static void lower(CompoundTag tag,int depth) {
        var pos=tag.getList("Pos",Tag.TAG_DOUBLE);
        tag.put("Pos",position(pos.getDouble(0),pos.getDouble(1)-depth,pos.getDouble(2)));
        if(attached(tag))tag.putInt("TileY",tag.getInt("TileY")-depth);
        for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))lower((CompoundTag)child,depth);
    }
    private static int project(CompoundTag tag,Face face,Spawn spawn,int size,int planet,RegistryAccess registries) {
        var topology=new dev.starfold.geometry.CubeSurfaceTopology(size);
        int ox=PlanetStorage.column(planet)*PlanetStorage.PLANET_STRIDE,oz=PlanetStorage.row(planet)*PlanetStorage.ROW_STRIDE;
        var pos=tag.getList("Pos",Tag.TAG_DOUBLE);boolean attached=attached(tag);
        var source=attached ? anchor(tag) : BlockPos.containing(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2));
        var route=topology.canonical(new Cell(face,source.getX(),0,source.getZ()));var c=route.cell();
        var targetAnchor=new BlockPos(ox+c.face().ordinal()*4096+c.u(),source.getY(),oz+c.v());
        Vec3 target;
        if(attached) {
            anchor(tag,targetAnchor);
            if(!tag.getString("id").equals("minecraft:leash_knot")) {
                boolean painting=tag.getString("id").equals("minecraft:painting");
                Direction facing=painting ? Direction.from2DDataValue(tag.getByte("facing")) : Direction.from3DDataValue(tag.getByte("Facing"));
                // HangingEntity.rotate mutates direction before mirror chooses its rotation.
                facing=spawn.rotation.rotate(facing);facing=spawn.mirror.getRotation(facing).rotate(facing);
                facing=Rotation.values()[route.turns()].rotate(facing);
                tag.putByte(painting ? "facing" : "Facing",(byte)(painting ? facing.get2DDataValue() : facing.get3DDataValue()));
            }
            target=attachedCenter(tag,registries);
        } else {
            var delta=dev.starfold.geometry.CubeSurfaceTopology.rotate(new Delta(1,0,0),route.turns());
            double x=pos.getDouble(0)-source.getX()-.5,z=pos.getDouble(2)-source.getZ()-.5;
            target=new Vec3(targetAnchor.getX()+.5+x*delta.u()-z*delta.v(),pos.getDouble(1),targetAnchor.getZ()+.5+x*delta.v()+z*delta.u());
        }
        // A hanging box center can leave its anchor's face and enter an ungenerated chart gap.
        int targetU=net.minecraft.util.Mth.floor(target.x)-ox-c.face().ordinal()*4096,targetV=net.minecraft.util.Mth.floor(target.z)-oz;
        if(targetU<0 || targetU>=size || targetV<0 || targetV>=size)
            throw new IllegalArgumentException("Structure entity center leaves generated face chart; whole candidate rejected");
        tag.put("Pos",position(target.x,target.y,target.z));
        for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))project((CompoundTag)child,face,spawn,size,planet,registries);
        return route.turns();
    }
    /** Centers from vanilla Painting/ItemFrame/LeashFenceKnotEntity bounding-box formulas. */
    static Vec3 attachedCenter(CompoundTag tag,RegistryAccess registries) {
        var anchor=anchor(tag);
        if(tag.getString("id").equals("minecraft:leash_knot"))return new Vec3(anchor.getX()+.5,anchor.getY()+.375,anchor.getZ()+.5);
        boolean painting=tag.getString("id").equals("minecraft:painting");
        Direction facing=painting ? Direction.from2DDataValue(tag.getByte("facing")) : Direction.from3DDataValue(tag.getByte("Facing"));
        var center=Vec3.atCenterOf(anchor).relative(facing,-.46875);
        if(painting) {
            var variant=registries.registryOrThrow(Registries.PAINTING_VARIANT).getOptional(ResourceLocation.parse(tag.getString("variant"))).orElseThrow();
            center=center.relative(facing.getCounterClockWise(),variant.width()%2==0 ? .5 : 0).relative(Direction.UP,variant.height()%2==0 ? .5 : 0);
        }
        return center;
    }
    public static List<Projected> forChunk(PlanetPlan.Site site,List<Spawn> spawns,int size,int planet,ChunkPos chunk,RegistryAccess registries) {
        var result=new ArrayList<Projected>();
        for(var spawn:spawns) {
            var projected=project(site,spawn,size,planet,registries);
            if(chunk(projected).equals(chunk))result.add(projected);
        }
        return List.copyOf(result);
    }
    private static void collect(CompoundTag tag,Map<UUID,CompoundTag> result) {
        result.put(tag.getUUID("UUID"),tag);
        for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))collect((CompoundTag)child,result);
    }
    public static void place(WorldGenLevel level,Projected projected) {
        var spawn=projected.spawn;
        var spawnRandom=net.minecraft.util.RandomSource.create(spawn.seed);
        var spawnLevel=(ServerLevelAccessor)java.lang.reflect.Proxy.newProxyInstance(ServerLevelAccessor.class.getClassLoader(),new Class<?>[]{ServerLevelAccessor.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getRandom"))return spawnRandom;
                    try { return method.invoke(level,args); }
                    catch(java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                });
        var entityTags=new HashMap<UUID,CompoundTag>();collect(projected.nbt,entityTags);
        var entity=EntityType.loadEntityRecursive(projected.nbt,level.getLevel(),e->{
            if(e instanceof BlockAttachedEntity attached) {
                if(!attached(entityTags.get(e.getUUID())))throw new IllegalArgumentException("Unsupported structure block-attached entity geometry");
                // NBT already contains final direction; setPos takes the anchor, never its box center.
                var anchor=anchor(entityTags.get(e.getUUID()));attached.setPos(anchor.getX(),anchor.getY(),anchor.getZ());
            } else {
                var pos=e.position();float yaw=e.rotate(spawn.rotation);
                yaw+=e.mirror(spawn.mirror)-e.getYRot();
                e.moveTo(pos.x,pos.y,pos.z,yaw,e.getXRot());
                e.moveTo(pos.x,pos.y,pos.z,e.rotate(Rotation.values()[projected.turns]),e.getXRot());
            }
            if(e instanceof Mob mob) {
                mob.getRandom().setSeed(spawn.seed);
                if(spawn.finalizeSpawn)net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob,spawnLevel,level.getCurrentDifficultyAt(e.blockPosition()),MobSpawnType.STRUCTURE,null);
            }
            return e;
        });
        if(entity==null)throw new IllegalStateException("Cannot load planned entity");
        if(entity instanceof BlockAttachedEntity && !entity.chunkPosition().equals(chunk(projected)))
            throw new IllegalStateException("Planned block-attached entity chunk differs from projection");
        level.addFreshEntityWithPassengers(entity);
    }
}
