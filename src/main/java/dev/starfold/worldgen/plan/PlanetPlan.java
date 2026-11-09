package dev.starfold.worldgen.plan;

import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Fixed L0 description. Resolved templates contain states only; vanilla recipes stay on the server. */
public final class PlanetPlan {
    public static final int FORMAT = 4;
    public static final String PLANNER = "traditional-terrain-3";
    public static final int PACKET_LIMIT = 900_000;
    public static final int MAX_PIECES = 1024;
    public static final int MAX_TOTAL_PIECES = 8192;
    public static final int ADAPT_RANGE = 12;
    public static final int TRANSFER_LIMIT = 16_000_000;
    private static final int RAW_LIMIT = 64_000_000;
    public record Bounds(int minU, int minY, int minV, int maxU, int maxY, int maxV) {
        public boolean contains(int u, int v) { return u >= minU && u <= maxU && v >= minV && v <= maxV; }
    }
    /** Rotation/mirror describe the source placement; the referenced visible template is already resolved. */
    public record Piece(int template, BlockPos origin, Rotation rotation, Mirror mirror, Bounds bounds, Bounds terrain, TerrainAdjustment adaptation) {
        public Piece { origin = origin.immutable();if(rotation==null)rotation=Rotation.NONE; }
    }
    public record Effects(int burial, double damage, List<dev.starfold.worldgen.plan.terrain.TerrainPlan.Ref> cuts) {
        public Effects { cuts = List.copyOf(cuts); }
        public static final Effects NONE = new Effects(0, 0, List.of());
    }
    public record Site(long id, ResourceLocation structure, Face face, int u, int v,
                       Bounds bounds, List<Piece> pieces, Effects effects) {
        public Site { pieces = List.copyOf(pieces); }
        public Site(long id, ResourceLocation structure, Face face, int u, int v, Bounds bounds, List<Piece> pieces) {
            this(id, structure, face, u, v, bounds, pieces, Effects.NONE);
        }
    }
    public record Voxel(int u, int y, int v, BlockState state) {}
    public static final class Template {
        private final List<Voxel> blocks;
        private final Map<Long,List<Voxel>> columns;
        private final int hash;
        public Template(List<Voxel> blocks) {
            this.blocks = blocks.stream().sorted(Comparator.comparingInt(Voxel::u)
                    .thenComparingInt(Voxel::v).thenComparingInt(Voxel::y)).toList();
            var columns = new HashMap<Long,List<Voxel>>();
            for (var b : this.blocks) columns.computeIfAbsent(columnKey(b.u,b.v), k -> new ArrayList<>()).add(b);
            columns.replaceAll((k,v) -> List.copyOf(v));
            this.columns = Collections.unmodifiableMap(columns);
            hash=this.blocks.hashCode();
        }
        public List<Voxel> blocks() { return blocks; }
        public List<Voxel> column(int u, int v) { return columns.getOrDefault(columnKey(u,v),List.of()); }
        @Override public boolean equals(Object other) { return other instanceof Template t && blocks.equals(t.blocks); }
        @Override public int hashCode() { return hash; }
    }
    /** An affine chart patch, shared by all columns in one 64-block bucket. */
    public record Projection(Site site, int targetU, int targetV, int sourceU, int sourceV, int turns, List<Piece> pieces) {
        public Cell source(int u, int v) {
            var d = CubeSurfaceTopology.rotate(new Delta(u-targetU,0,v-targetV),-turns);
            return new Cell(site.face,sourceU+d.u(),0,sourceV+d.v());
        }
    }
    private final long indexNanos,compressionNanos;
    public long indexNanos() { return indexNanos; }
    public long compressionNanos() { return compressionNanos; }
    private final byte[] compressed;
    private final String identity;
    private final int size;
    private final dev.starfold.worldgen.plan.terrain.TerrainPlan terrain;
    public dev.starfold.worldgen.plan.terrain.TerrainPlan terrain() { return terrain; }
    private final List<Site> sites;
    private final List<Template> templates;
    private record Adjustment(TerrainAdjustment kind,int height,double weight,List<dev.starfold.worldgen.plan.terrain.TerrainPlan.Ref> cuts) {
        int apply(int raw) {
            int target=kind==TerrainAdjustment.BURY || kind==TerrainAdjustment.ENCAPSULATE ? Math.max(raw,height) : height;
            return (int)Math.round(raw+(target-raw)*weight);
        }
    }
    private record ColumnQuery(List<Adjustment> adjustments,boolean occupied,SortedMap<Integer,BlockState> states, Map<Integer,Long> owners) {}
    private static final ColumnQuery EMPTY_QUERY=new ColumnQuery(List.of(),false,Collections.emptySortedMap(),Map.of());
    private final java.util.concurrent.ConcurrentMap<Long,java.util.concurrent.atomic.AtomicReferenceArray<ColumnQuery>> queries=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long,List<Projection>> buckets;
    public PlanetPlan(int size,dev.starfold.worldgen.plan.terrain.TerrainPlan terrain,List<Site> sites,List<Template> templates) {
        this.terrain=terrain;
        this.size = size;
        this.sites = List.copyOf(sites);
        this.templates = List.copyOf(templates);
        long indexStart=System.nanoTime();
        var buckets = new HashMap<Long,List<Projection>>();
        var topology = new CubeSurfaceTopology(size);
        long indexWork=0;
        for (var s : this.sites) {
            var seen = new HashSet<Long>();
            var extent = extent(s);
            for (int u=extent.minU;u<=extent.maxU;u++) for (int v=extent.minV;v<=extent.maxV;v++) {
                var route = topology.canonical(new Cell(s.face,u,0,v));
                var c = route.cell();
                long key = bucketKey(c.face(),c.u(),c.v());
                if (!seen.add(key*4+route.turns())) continue;
                var projection=new Projection(s,c.u(),c.v(),u,v,route.turns(),List.of());
                var a=projection.source((c.u()>>6)*64,(c.v()>>6)*64);
                var b=projection.source((c.u()>>6)*64+63,(c.v()>>6)*64+63);
                int minU=Math.min(a.u(),b.u()),minV=Math.min(a.v(),b.v()),maxU=Math.max(a.u(),b.u()),maxV=Math.max(a.v(),b.v());
                indexWork+=s.pieces.size();
                if(indexWork>4_000_000)throw new IllegalArgumentException("Plan bucket work limit");
                var local=s.pieces.stream().filter(piece->{
                    var bounds=piece.bounds;var t=piece.terrain;
                    int margin=piece.adaptation==TerrainAdjustment.NONE ? 0 : ADAPT_RANGE;
                    return bounds.maxU>=minU && bounds.minU<=maxU && bounds.maxV>=minV && bounds.minV<=maxV
                            || piece.adaptation!=TerrainAdjustment.NONE && t.maxU+margin>=minU && t.minU-margin<=maxU && t.maxV+margin>=minV && t.minV-margin<=maxV;
                }).toList();
                if(!local.isEmpty())buckets.computeIfAbsent(key,k->new ArrayList<>()).add(new Projection(s,c.u(),c.v(),u,v,route.turns(),local));
            }
        }
        buckets.replaceAll((k,v)->List.copyOf(v));
        this.buckets = Collections.unmodifiableMap(buckets);
        indexNanos=System.nanoTime()-indexStart;
        long compressionStart=System.nanoTime();
        this.compressed=encode();
        compressionNanos=System.nanoTime()-compressionStart;
        try { this.identity=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(compressed)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public boolean equals(Object other) { return other instanceof PlanetPlan p && size==p.size && sites.equals(p.sites) && templates.equals(p.templates) && terrain.recipe().equals(p.terrain.recipe()) && identity.equals(p.identity); }
    @Override public int hashCode() { return Objects.hash(size,sites,templates); }
    public String identity() { return identity; }
    public int format() { return FORMAT; }
    public String planner() { return PLANNER; }
    public int size() { return size; }
    public List<Site> sites() { return sites; }
    public List<Template> templates() { return templates; }
    public List<Projection> at(Face face, int u, int v) { return buckets.getOrDefault(bucketKey(face,u,v),List.of()); }
    public Site site(long id) { return sites.stream().filter(s->s.id==id).findFirst().orElseThrow(); }
    public static Bounds extent(Site s) {
        var b=s.bounds;
        for(var piece:s.pieces) {
            var t=piece.terrain;
            int margin=piece.adaptation==TerrainAdjustment.NONE ? 0 : ADAPT_RANGE;
            b=union(b,new Bounds(t.minU-margin,t.minY,t.minV-margin,t.maxU+margin,t.maxY,t.maxV+margin));
        }
        return b;
    }
    public static Bounds union(Bounds a,Bounds b) {
        return new Bounds(Math.min(a.minU,b.minU),Math.min(a.minY,b.minY),Math.min(a.minV,b.minV),
                Math.max(a.maxU,b.maxU),Math.max(a.maxY,b.maxY),Math.max(a.maxV,b.maxV));
    }
    public static boolean footprint(Site site,int u,int v) {
        for(var piece:site.pieces) {
            if(piece.bounds.contains(u,v))return true;
            if(piece.adaptation!=TerrainAdjustment.NONE && distance(piece.terrain,u,v)<ADAPT_RANGE)return true;
        }
        return false;
    }
    private static Bounds footprintBounds(Piece piece) {
        var t=piece.terrain;
        return piece.adaptation==TerrainAdjustment.NONE ? piece.bounds : union(piece.bounds,
                new Bounds(t.minU-ADAPT_RANGE,t.minY,t.minV-ADAPT_RANGE,t.maxU+ADAPT_RANGE,t.maxY,t.maxV+ADAPT_RANGE));
    }
    public static long footprintColumns(Site site) {
        long columns=0;
        for(var piece:site.pieces) {
            var b=footprintBounds(piece);columns+=(long)(b.maxU-b.minU+1)*(b.maxV-b.minV+1);
        }
        return columns;
    }
    /** Enumerate each piece once, rather than testing every piece for each site-bbox column. */
    public static BitSet footprintCells(Site site,int size) {
        var own=new BitSet(6*size*size);var topology=new CubeSurfaceTopology(size);
        for(var piece:site.pieces) {
            var b=footprintBounds(piece);
            for(int u=b.minU;u<=b.maxU;u++)for(int v=b.minV;v<=b.maxV;v++) {
                if(!piece.bounds.contains(u,v) && (piece.adaptation==TerrainAdjustment.NONE || distance(piece.terrain,u,v)>=ADAPT_RANGE))continue;
                var c=topology.canonical(new Cell(site.face,u,0,v)).cell();
                own.set((c.face().ordinal()*size+c.u())*size+c.v());
            }
        }
        return own;
    }
    private static double distance(Bounds b,int u,int v) {
        return Math.hypot(Math.max(0,Math.max(b.minU-u,u-b.maxU)),Math.max(0,Math.max(b.minV-v,v-b.maxV)));
    }
    /** Bounded L2: only the raw L1 height and fixed piece boxes participate. Ties keep piece order. */
    public static int ground(Site site,int u,int v,int raw) { return ground(site.pieces,u,v,raw); }
    private static int ground(List<Piece> pieces,int u,int v,int raw) {
        double strongest=0;int result=raw;
        for(var piece:pieces) {
            if(piece.adaptation==TerrainAdjustment.NONE)continue;
            var b=piece.terrain;
            double d=distance(b,u,v);
            if(d>=ADAPT_RANGE)continue;
            double t=d/ADAPT_RANGE,weight=1-t*t*(3-2*t);
            int target=switch(piece.adaptation) {
                case NONE -> raw;
                case BEARD_THIN, BEARD_BOX -> b.minY-1;
                case BURY -> Math.max(raw,b.minY+(b.maxY-b.minY)/2);
                case ENCAPSULATE -> Math.max(raw,b.maxY+1);
            };
            if(weight>strongest) {strongest=weight;result=(int)Math.round(raw+(target-raw)*weight);}
        }
        return result;
    }
    private ColumnQuery query(Face face,int u,int v) {
        long key=bucketKey(face,u,v);var bucket=queries.get(key);
        if(bucket==null) {
            if(!buckets.containsKey(key))return EMPTY_QUERY;
            bucket=queries.computeIfAbsent(key,k->new java.util.concurrent.atomic.AtomicReferenceArray<>(4096));
        }
        int index=(u&63)*64+(v&63);var cached=bucket.get(index);
        if(cached!=null)return cached;
        var adjustments=new ArrayList<Adjustment>();var states=new TreeMap<Integer,BlockState>();var owners=new HashMap<Integer,Long>();boolean occupied=false;
        for(var projection:at(face,u,v)) {
            var c=projection.source(u,v);double strongest=0;Adjustment adjustment=null;
            for(var piece:projection.pieces) {
                occupied|=piece.bounds.contains(c.u(),c.v());
                if(piece.adaptation!=TerrainAdjustment.NONE && projection.site.effects.burial == 0) {
                    double d=distance(piece.terrain,c.u(),c.v());
                    if(d<ADAPT_RANGE) {
                        double t=d/ADAPT_RANGE,weight=1-t*t*(3-2*t);
                        if(weight>strongest) {
                            strongest=weight;var bounds=piece.terrain;
                            int height=switch(piece.adaptation) {
                                case BEARD_THIN,BEARD_BOX -> bounds.minY-1;
                                case BURY -> bounds.minY+(bounds.maxY-bounds.minY)/2;
                                case ENCAPSULATE -> bounds.maxY+1;
                                default -> throw new AssertionError();
                            };
                            adjustment=new Adjustment(piece.adaptation,height,weight,projection.site.effects.cuts);
                        }
                    }
                }
                if(projection.site.bounds.contains(c.u(),c.v())) {
                    var site = projection.site;
                    var point = dev.starfold.worldgen.PlanetTerrain.direction(new dev.starfold.chart.PlanetShape(size),
                        face, u + .5, v + .5);
                    for(var block:templates.get(piece.template).column(c.u()-piece.origin.getX(),c.v()-piece.origin.getZ())) {
                        int y = piece.origin.getY() + block.y - site.effects.burial;
                        var state = block.state.rotate(Rotation.values()[projection.turns]);
                        if (site.effects.burial > 0 && state.isAir()) {
                            states.remove(y); owners.remove(y); continue;
                        }
                        long hash = dev.starfold.worldgen.surface.Scatter.mix(site.id ^ (long)c.u()<<40
                            ^ (long)c.v()<<20 ^ y);
                        if ((hash >>> 11) * 0x1.0p-53 < site.effects.damage
                            || site.effects.cuts.stream().anyMatch(ref -> terrain.cuts(ref, point, y)))
                            state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                        states.put(y, state);
                        owners.put(y, site.id);
                    }
                }
            }
            if(adjustment!=null)adjustments.add(adjustment);
        }
        var built=adjustments.isEmpty() && !occupied && states.isEmpty() ? EMPTY_QUERY
                : new ColumnQuery(List.copyOf(adjustments),occupied,Collections.unmodifiableSortedMap(states),Map.copyOf(owners));
        bucket.compareAndSet(index,null,built);return bucket.get(index);
    }
    public int ground(Face face,int u,int v,int raw) {
        var query=query(face,u,v);
        for(var adjustment:query.adjustments) {
            raw = adjustment.apply(raw);
            if(!adjustment.cuts.isEmpty()) {
                var p=dev.starfold.worldgen.PlanetTerrain.direction(new dev.starfold.chart.PlanetShape(size),face,u+.5,v+.5);
                for(var ref:adjustment.cuts)while(raw > -1024 && terrain.cuts(ref,p,raw))raw--;
            }
        }
        return raw;
    }
    public boolean occupied(Face face,int u,int v) { return query(face,u,v).occupied; }
    public SortedMap<Integer,BlockState> column(Face face,int u,int v) { return query(face,u,v).states; }
    public boolean owns(Face face,int u,int v,int y,long site) {
        return Objects.equals(query(face,u,v).owners.get(y), site);
    }
    public void validateGeneration(int floor) {
        for(var site:sites)
            for(var piece:site.pieces)
                if(piece.bounds.minY-site.effects.burial<=floor)
                    for(var block:templates.get(piece.template).blocks)
                        if(piece.origin.getY()+block.y-site.effects.burial<=floor)
                            throw new IllegalArgumentException("Structure intersects core shell");
    }
    static long columnKey(int u,int v) { return net.minecraft.world.level.ChunkPos.asLong(v,u); }
    static long bucketKey(Face f,int u,int v) { return ((long)f.ordinal()<<40) | ((long)(u>>6)<<20) | (v>>6); }
    public static long cellKey(Cell c) { return ((long)c.face().ordinal()<<24) | ((long)c.u()<<12) | c.v(); }

    public byte[] compressed() { return compressed.clone(); }
    private byte[] encode() {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(bytes),65536))) { write(out); }
            return bytes.toByteArray();
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public void writePacket(RegistryFriendlyByteBuf out) {
        if(compressed.length>PACKET_LIMIT)throw new IllegalArgumentException("Plan compressed limit");
        out.writeByteArray(compressed);
    }
    public static PlanetPlan readPacket(RegistryFriendlyByteBuf in) { return decode(in.readByteArray(PACKET_LIMIT)); }
    public static PlanetPlan decode(byte[] data) {
        if(data.length>TRANSFER_LIMIT)throw new IllegalArgumentException("Plan compressed limit");
        try(var zip=new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] raw=zip.readNBytes(RAW_LIMIT+1);
            if(raw.length>RAW_LIMIT)throw new IOException("Plan expansion limit");
            try(var in=new DataInputStream(new ByteArrayInputStream(raw))) {
                if(in.readInt()!=FORMAT || !in.readUTF().equals(PLANNER))throw new IOException("Unsupported plan version");
                int size=in.readInt();
                if(size<256 || size>2048 || size%16!=0)throw new IOException("Plan size");
                var terrain=dev.starfold.worldgen.plan.terrain.TerrainPlan.read(in,size);
                int count=count(in,8192);
                var templates=new ArrayList<Template>();
                int total=0;
                for(int i=0;i<count;i++) {
                    int n=count(in,262144); total+=n;
                    if(total>4_000_000)throw new IOException("Plan block limit");
                    var blocks=new ArrayList<Voxel>();
                    var positions=new HashSet<BlockPos>();
                    for(int j=0;j<n;j++) {
                        int u=in.readShort(),y=in.readShort(),v=in.readShort();
                        var state=Block.BLOCK_STATE_REGISTRY.byId(in.readInt());
                        if(Math.abs(u)>128 || Math.abs(v)>128 || y < -512 || y>512 || state==null
                                || !positions.add(new BlockPos(u,y,v)))throw new IOException("Plan voxel");
                        blocks.add(new Voxel(u,y,v,state));
                    }
                    templates.add(new Template(blocks));
                }
                int n=count(in,8192);
                var sites=new ArrayList<Site>();
                var ids = new HashSet<Long>();
                long indexColumns=0, footprintColumns=0, referencedBlocks=0;int totalPieces=0;

                for(int i=0;i<n;i++) {
                    long id=in.readLong(); if(!ids.add(id))throw new IOException("Duplicate plan site");
                    var structure=ResourceLocation.parse(in.readUTF());
                    int face=in.readUnsignedByte(),u=in.readInt(),v=in.readInt();
                    if(face>=6 || u<0 || u>=size || v<0 || v>=size)throw new IOException("Plan origin");
                    var bounds=readBounds(in,size);
                    int k=count(in,MAX_PIECES);totalPieces+=k;
                    if(k==0 || totalPieces>MAX_TOTAL_PIECES)throw new IOException("Plan piece count");
                    var pieces=new ArrayList<Piece>();
                    for(int j=0;j<k;j++) {
                        int template=in.readInt();
                        var origin=new BlockPos(in.readInt(),in.readInt(),in.readInt());
                        int rotation=in.readUnsignedByte(),mirror=in.readUnsignedByte();
                        if(template<0 || template>=templates.size() || rotation>=4 || mirror>=3
                                || origin.getX() < -256 || origin.getX()>size+256 || origin.getZ() < -256 || origin.getZ()>size+256
                                || origin.getY() < -1024 || origin.getY()>=320)throw new IOException("Plan piece");
                        var pieceBounds=readBounds(in,size);
                        var terrainBounds=readBounds(in,size);
                        int adaptation=in.readUnsignedByte();
                        if(adaptation>=TerrainAdjustment.values().length)throw new IOException("Plan adaptation");
                        if(!bounds.contains(pieceBounds.minU,pieceBounds.minV) || !bounds.contains(pieceBounds.maxU,pieceBounds.maxV)
                                || pieceBounds.minY<bounds.minY || pieceBounds.maxY>bounds.maxY)throw new IOException("Piece outside site");
                        referencedBlocks+=templates.get(template).blocks.size();
                        if(referencedBlocks>4_000_000)throw new IOException("Plan template reference limit");
                        for(var block:templates.get(template).blocks) {
                            int x=origin.getX()+block.u,y=origin.getY()+block.y,z=origin.getZ()+block.v;
                            if(!pieceBounds.contains(x,z) || y<pieceBounds.minY || y>pieceBounds.maxY)throw new IOException("Template outside piece bounds");
                        }
                        pieces.add(new Piece(template,origin,Rotation.values()[rotation],Mirror.values()[mirror],pieceBounds,terrainBounds,TerrainAdjustment.values()[adaptation]));
                    }
                    int burial = in.readInt();
                    double damage = in.readDouble();
                    int cutCount = count(in, 64);
                    var cuts = new ArrayList<dev.starfold.worldgen.plan.terrain.TerrainPlan.Ref>();
                    for (int j = 0; j < cutCount; j++) {
                        int kind = in.readUnsignedByte();
                        long target = in.readLong();
                        var kinds = dev.starfold.worldgen.plan.terrain.TerrainPlan.Kind.values();
                        if (kind >= kinds.length || kinds[kind] == dev.starfold.worldgen.plan.terrain.TerrainPlan.Kind.SITE)
                            throw new IOException("Invalid site cut kind");
                        cuts.add(new dev.starfold.worldgen.plan.terrain.TerrainPlan.Ref(kinds[kind], target));
                    }
                    if (burial < 0 || burial > 64 || bounds.minY - burial < -1024
                        || !Double.isFinite(damage) || damage < 0 || damage > 1)
                        throw new IOException("Invalid site effects");
                    for (var ref : cuts) if (!terrain.validReference(ref, Set.of(), true))
                        throw new IOException("Invalid site cut reference");
                    var site=new Site(id,structure,Face.VALUES[face],u,v,bounds,pieces,new Effects(burial, damage, cuts));
                    var e=extent(site);
                    indexColumns+=(long)(e.maxU-e.minU+1)*(e.maxV-e.minV+1);
                    if(e.maxU-e.minU>512 || e.maxV-e.minV>512 || indexColumns>4_000_000)throw new IOException("Plan footprint limit");
                    footprintColumns+=footprintColumns(site);
                    if(footprintColumns>4_000_000)throw new IOException("Plan footprint work limit");
                    sites.add(site);
                }
                terrain.validateReferences(ids, true);
                if(in.read()!=-1)throw new IOException("Trailing plan bytes");
                return new PlanetPlan(size,terrain,sites,templates);
            }
        } catch(IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid planet plan",e); }
    }
    private void write(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT); out.writeUTF(PLANNER); out.writeInt(size);terrain.write(out);
        out.writeInt(templates.size());
        for(var t:templates) {
            out.writeInt(t.blocks.size());
            for(var b:t.blocks) { out.writeShort(b.u);out.writeShort(b.y);out.writeShort(b.v);out.writeInt(Block.getId(b.state)); }
        }
        out.writeInt(sites.size());
        for(var s:sites) {
            out.writeLong(s.id);out.writeUTF(s.structure.toString());out.writeByte(s.face.ordinal());out.writeInt(s.u);out.writeInt(s.v);
            writeBounds(out,s.bounds);out.writeInt(s.pieces.size());
            for(var p:s.pieces) {
                out.writeInt(p.template);out.writeInt(p.origin.getX());out.writeInt(p.origin.getY());out.writeInt(p.origin.getZ());
                out.writeByte(p.rotation.ordinal());out.writeByte(p.mirror.ordinal());writeBounds(out,p.bounds);writeBounds(out,p.terrain);out.writeByte(p.adaptation.ordinal());
            }
            out.writeInt(s.effects.burial); out.writeDouble(s.effects.damage); out.writeInt(s.effects.cuts.size());
            for (var ref : s.effects.cuts) { out.writeByte(ref.kind().ordinal()); out.writeLong(ref.id()); }
        }
    }
    private static void writeBounds(DataOutputStream out,Bounds b) throws IOException {
        out.writeInt(b.minU);out.writeInt(b.minY);out.writeInt(b.minV);out.writeInt(b.maxU);out.writeInt(b.maxY);out.writeInt(b.maxV);
    }
    private static Bounds readBounds(DataInputStream in,int size) throws IOException {
        var b=new Bounds(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
        if(b.minU < -256 || b.minV < -256 || b.maxU>=size+256 || b.maxV>=size+256 || b.minY < -1024 || b.maxY>=320
                || b.minU>b.maxU || b.minY>b.maxY || b.minV>b.maxV || b.maxU-b.minU>512 || b.maxV-b.minV>512)
            throw new IOException("Plan bounds "+b);
        return b;
    }
    private static int count(DataInputStream in,int max) throws IOException {
        int n=in.readInt();if(n<0 || n>max)throw new IOException("Plan count");return n;
    }
}
