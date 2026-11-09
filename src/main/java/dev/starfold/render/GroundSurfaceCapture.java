package dev.starfold.render;

import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.function.IntFunction;

/** Bounded exterior comparison followed by full-height capture of genuinely changed chunks. */
final class GroundSurfaceCapture {
    private static final int[][] NEIGHBOURS={{0,0},{-1,0},{1,0},{0,-1},{0,1}};
    private final ChunkAccess chunk;
    private final GroundSurface.Run[][] columns = new GroundSurface.Run[256][];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int column;
    private Scan scan, referenceScan;
    private final dev.starfold.worldgen.surface.SurfaceFunction function;
    private final dev.starfold.geometry.CubeSurfaceTopology.Face face;
    private final int u,v;
    private BlockState[] reference;
    private boolean different, full, invalidated;
    private int scanFloor;

    GroundSurfaceCapture(ChunkAccess chunk) { this(chunk,null,0); }
    GroundSurfaceCapture(ChunkAccess chunk,dev.starfold.worldgen.surface.SurfaceFunction function,long key) {
        this.chunk = chunk; this.function=function; full=function==null;
        face=dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[(int)(key/16384)];
        u=(int)(key%128)*16; v=(int)(key/128%128)*16;
    }

    GroundSurfaceCapture changed(ChunkAccess chunk) {
        if(this.chunk!=chunk) return new GroundSurfaceCapture(chunk,function,face.ordinal()*16384L+u/16+v/16*128L);
        if(column>0 || scan!=null || referenceScan!=null) invalidated=true;
        return this;
    }
    ChunkAccess chunk() { return chunk; }
    boolean invalidated() { return invalidated; }

    GroundSurfaceCapture restart(ChunkAccess chunk) {
        if (this.chunk != chunk) return new GroundSurfaceCapture(chunk,function,face.ordinal()*16384L+u/16+v/16*128L);
        column = 0;
        scan = referenceScan = null;
        reference=null; different=false; invalidated=false; full=function==null;
        return this;
    }

    int step(int budget) {
        int reads = 0;
        while (column < 256 && reads < budget) {
            int x = column & 15, z = column >> 4;
            if(referenceScan!=null) {
                referenceScan.accept(reference[referenceScan.y-function.terrain().floor()]); reads++;
                if(referenceScan.done) {
                    if(!java.util.Arrays.equals(columns[column],referenceScan.result())) {
                        // A real exterior difference admits one full-height actual capture, not a
                        // full underground actual/reference scan for every naturally marked chunk.
                        different=true; full=true; column=0;
                    } else column++;
                    referenceScan=null; reference=null;
                }
                continue;
            }
            if (scan == null) {
                scanFloor=chunk.getMinBuildHeight();
                if(!full) {
                    int low=function.groundTop(face,u+x,v+z);
                    for(int[] d:NEIGHBOURS) {
                        low=Math.min(low,function.groundTop(face,u+x+d[0],v+z+d[1]));
                        int a=x+d[0],b=z+d[1];
                        if(a>=0 && a<16 && b>=0 && b<16)
                            low=Math.min(low,chunk.getHeight(Heightmap.Types.WORLD_SURFACE,a,b));
                    }
                    scanFloor=Math.max(scanFloor,low-4);
                }
                scan=new Scan(chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z),scanFloor);
            }
            scan.accept(
                    chunk.getBlockState(
                            pos.set(
                                    chunk.getPos().getMinBlockX() + x,
                                    scan.y,
                                    chunk.getPos().getMinBlockZ() + z)));
            reads++;
            if (scan.done) {
                columns[column] = scan.result();
                scan = null;
                if(full) { different=true; column++; }
                else {
                    // Compare the same exterior band; only admitted captures scan all buried layers.
                    reference=function.blocks(face,u+x,v+z,scanFloor);
                    int high=reference.length-1;
                    while(high>0 && reference[high].isAir()) high--;
                    referenceScan=new Scan(high+function.terrain().floor(),scanFloor);
                }
            }
        }
        return reads;
    }

    static GroundSurface.Run[] scan(int top, int floor, IntFunction<BlockState> source) {
        var scan = new Scan(top, floor);
        while (!scan.done) scan.accept(source.apply(scan.y));
        return scan.result();
    }

    private static final class Scan {
        final int floor;
        final ArrayList<GroundSurface.Run> runs = new ArrayList<>();
        int y, stop, top, bottom, state;
        boolean found, active, done;

        Scan(int y, int floor) {
            this.y = y;
            this.floor = floor;
            stop = floor;
        }

        void accept(BlockState raw) {
            var shown = VoxelMesher.shown(raw);
            boolean solid = !shown.isAir();
            boolean split = active && solid && Block.getId(shown) != state;
            if (active && (!solid || split)) {
                finish();
            }
            if (solid) {
                if (!found) {
                    found = true;
                    stop = floor;
                }
                if (!active) {
                    top = y;
                    state = Block.getId(shown);
                    active = true;
                }
                bottom = y;
            }
            if (y <= stop) {
                if (active) finish();
                done = true;
            }
            y--;
        }

        void finish() {
            runs.add(new GroundSurface.Run(top, bottom, state));
            active = false;
        }

        GroundSurface.Run[] result() {
            if (runs.isEmpty())
                return new GroundSurface.Run[] {
                    new GroundSurface.Run(
                            floor - 1, floor - 1, Block.getId(Blocks.AIR.defaultBlockState()))
                };
            var source = new ArrayList<dev.starfold.worldgen.lod.VoxelColumn.Run>();
            for (int i = runs.size() - 1; i >= 0; i--) {
                var r = runs.get(i);
                source.add(new dev.starfold.worldgen.lod.VoxelColumn.Run(r.bottom(),r.top()+1,r.material()));
            }
            var bounded = dev.starfold.worldgen.lod.VoxelColumn.compose(source).runs();
            var result = new GroundSurface.Run[bounded.size()];
            for (int i = 0; i < result.length; i++) {
                var r = bounded.get(bounded.size()-1-i);
                result[i] = new GroundSurface.Run(r.top()-1,r.bottom(),Block.getId(r.block()));
            }
            return result;
        }
    }

    boolean done() {
        return column == 256;
    }

    boolean matchesBaseline() { if(!done()) throw new IllegalStateException("Incomplete comparison"); return !different; }

    GroundSurface result(long revision) {
        if (!done()) throw new IllegalStateException("Incomplete surface");
        return new GroundSurface(revision, columns);
    }
}
