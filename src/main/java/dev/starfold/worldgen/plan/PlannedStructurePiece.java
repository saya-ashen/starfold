package dev.starfold.worldgen.plan;

import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.universe.SystemChunkGenerator;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** A chunk-local projection of a fixed site; source vanilla pieces are always fresh on replay. */
public final class PlannedStructurePiece extends StructurePiece {
    private static final DeferredRegister<StructurePieceType> TYPES=DeferredRegister.create(BuiltInRegistries.STRUCTURE_PIECE,"starfold");
    private static final StructurePieceType TYPE=PlannedStructurePiece::new;
    static { TYPES.register("planned",()->TYPE); }
    private final int planet;
    private final long site;
    public static void initialize(IEventBus bus) { TYPES.register(bus); }
    public PlannedStructurePiece(int planet,long site,BoundingBox bounds) {
        super(TYPE,0,bounds);this.planet=planet;this.site=site;
    }
    PlannedStructurePiece(StructurePieceSerializationContext context,CompoundTag tag) {
        super(TYPE,tag);planet=tag.getInt("planet");site=tag.getLong("site");
    }
    @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag) {
        tag.putInt("planet",planet);tag.putLong("site",site);
    }
    @Override public void postProcess(WorldGenLevel level,StructureManager manager,ChunkGenerator generator,RandomSource random,
                                      BoundingBox area,ChunkPos chunk,BlockPos origin) {
        place(level,generator,area,chunk,entity->StructureEntities.place(level,entity));
    }
    void place(WorldGenLevel level,ChunkGenerator generator,BoundingBox area,ChunkPos chunk,
               java.util.function.Consumer<StructureEntities.Projected> spawn) {
        var source=((SystemChunkGenerator)generator).planet(planet);
        var planned=source.terrain().plan().site(site);
        var result=source.planStore().replay(planned);
        int ox=PlanetStorage.column(planet)*PlanetStorage.PLANET_STRIDE,oz=PlanetStorage.row(planet)*PlanetStorage.ROW_STRIDE;
        var function = new dev.starfold.worldgen.surface.SurfaceFunction(source.terrain());
        var columns = new java.util.HashMap<Long,net.minecraft.world.level.block.state.BlockState[]>();
        for(var entry:result.blocks().entrySet()) {
            var p=entry.getKey();
            var route=source.terrain().shape().topology().canonical(new Cell(planned.face(),p.getX(),0,p.getZ()));
            var c=route.cell();
            int y = p.getY() - planned.effects().burial();
            var target=new BlockPos(ox+c.face().ordinal()*4096+c.u(),y,oz+c.v());
            if(!area.isInside(target) || !boundingBox.isInside(target))continue;
            if (y <= source.terrain().floor() || y >= dev.starfold.worldgen.PlanetTerrain.MAX_Y) continue;
            var states = columns.computeIfAbsent(PlanetPlan.cellKey(c),
                key -> function.blocks(c.face(), c.u(), c.v()));
            var state=states[y-source.terrain().floor()];
            var entity=source.terrain().plan().owns(c.face(),c.u(),c.v(),y,planned.id())
                && state == entry.getValue().rotate(Rotation.values()[route.turns()]) ? result.entities().get(p) : null;
            if(entity!=null)level.setBlock(target,Blocks.BARRIER.defaultBlockState(),2);
            level.setBlock(target,state,2);
            if(entity!=null) {
                var actual=level.getBlockEntity(target);
                if(actual!=null) {
                    var data=entity.saveWithFullMetadata(level.registryAccess());
                    data.putInt("x",target.getX());data.putInt("y",target.getY());data.putInt("z",target.getZ());
                    actual.loadWithComponents(data,level.registryAccess());actual.setChanged();
                }
            }
        }
        for(var entity:StructureEntities.forChunk(planned,result.spawns(),source.terrain().shape().size(),planet,chunk,level.registryAccess()))
            spawn.accept(entity);
    }
    public static void createStarts(SystemChunkGenerator system,int planet,RegistryAccess registries,StructureManager manager,ChunkAccess chunk) {
        var terrain=system.planet(planet).terrain();var plan=terrain.plan();
        var pos=chunk.getPos();
        int ox=PlanetStorage.column(planet)*PlanetStorage.PLANET_STRIDE,oz=PlanetStorage.row(planet)*PlanetStorage.ROW_STRIDE;
        int u=Math.floorMod(pos.getMinBlockX()-ox,4096),v=pos.getMinBlockZ()-oz;
        var face=dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[(pos.getMinBlockX()-ox)/4096];
        var byStructure=new java.util.LinkedHashMap<Structure,java.util.List<StructurePiece>>();
        var seen=new java.util.HashSet<Long>();
        java.util.function.Consumer<PlanetPlan.Site> add=site->{
            if(!seen.add(site.id()))return;
            var structure=registries.registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).get(site.structure());
            var bounds=new BoundingBox(pos.getMinBlockX(),site.bounds().minY()-site.effects().burial(),pos.getMinBlockZ(),pos.getMaxBlockX(),site.bounds().maxY(),pos.getMaxBlockZ());
            byStructure.computeIfAbsent(structure,k->new java.util.ArrayList<>()).add(new PlannedStructurePiece(planet,site.id(),bounds));
        };
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(var projection:plan.at(face,u+x,v+z)) {
            var site=projection.site();var at=projection.source(u+x,v+z);
            if(site.bounds().contains(at.u(),at.v()))add.accept(site);
        }
        for(var site:system.planet(planet).planStore().entitySites(planet,pos))add.accept(site);
        var section=SectionPos.bottomOf(chunk);
        byStructure.forEach((structure,pieces)->manager.setStartForStructure(section,structure,
                new StructureStart(structure,pos,0,new PiecesContainer(pieces)),chunk));
    }
}
