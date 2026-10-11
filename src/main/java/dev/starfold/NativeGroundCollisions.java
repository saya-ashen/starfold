package dev.starfold;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Lazy real VoxelShapes in the observer's upright chart, including alias cells and the mask. */
public final class NativeGroundCollisions implements Iterable<VoxelShape> {
    private final BlockGetter view;private final CollisionContext context;private final AABB box;
    /** Client: the world is already in the observer's coordinates; Level supplies the corner mask. */
    public NativeGroundCollisions(Level level,Entity entity,AABB box){this.view=level;this.context=entity==null?CollisionContext.empty():CollisionContext.of(entity);this.box=box;}
    public NativeGroundCollisions(Level level,Entity entity,AABB box,GroundSpace.Config mask){this.view=new NativeGroundTerrain(level,entity,mask).view();this.context=entity==null?CollisionContext.empty():CollisionContext.of(entity);this.box=box;}
    @Override public Iterator<VoxelShape> iterator(){
        return new Iterator<>(){
            final int minX=(int)Math.floor(box.minX)-1,maxX=(int)Math.floor(box.maxX)+1,minY=(int)Math.floor(box.minY)-1,maxY=(int)Math.floor(box.maxY)+1,minZ=(int)Math.floor(box.minZ)-1,maxZ=(int)Math.floor(box.maxZ)+1;
            int x=minX,y=minY,z=minZ;boolean ended;VoxelShape next;final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
            private void advance(){if(++x>maxX){x=minX;if(++z>maxZ){z=minZ;if(++y>maxY)ended=true;}}}
            private void find(){while(!ended&&next==null){pos.set(x,y,z);var state=view.getBlockState(pos);var shape=state.getCollisionShape(view,pos,context).move(x,y,z);advance();if(!shape.isEmpty()&&Shapes.joinIsNotEmpty(shape,Shapes.create(box),BooleanOp.AND))next=shape;}}
            @Override public boolean hasNext(){find();return next!=null;}
            @Override public VoxelShape next(){find();if(next==null)throw new NoSuchElementException();var out=next;next=null;return out;}
        };
    }
    private NativeGroundCollisions(){throw new AssertionError();}
}
