package dev.starfold;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

/** Sable already supplies the hull pose. Keep its rotation: the stored aircraft is fixed to the deck. */
public final class NativeAircraftDockRenderer implements BlockEntityRenderer<NativeAircraftDock.Dock> {
    static void initialize(net.neoforged.bus.api.IEventBus bus){bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e)->e.registerBlockEntityRenderer(NativeAircraftDock.type,c->new NativeAircraftDockRenderer()));}
    private static final class Cached {long revision;Entity entity;Cached(long revision){this.revision=revision;}}
    private final java.util.Map<NativeAircraftDock.Dock,Cached> cache=new java.util.WeakHashMap<>();
    @Override public void render(NativeAircraftDock.Dock dock,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        if(dock.stored==null||dock.getLevel()==null){cache.remove(dock);return;}
        var entry=cache.get(dock);
        try{
            if(entry==null||entry.revision!=dock.revision){entry=new Cached(dock.revision);cache.put(dock,entry);entry.entity=EntityType.create(dock.stored.copy(),dock.getLevel()).orElse(null);if(entry.entity!=null){entry.entity.setYRot(dock.yaw);entry.entity.yRotO=dock.yaw;entry.entity.setXRot(0);entry.entity.xRotO=0;}}
            if(entry.entity==null)return;
            // Isolate even a mod renderer which throws with an unbalanced stack. Do not tick/add this display-only entity.
            var pose=new PoseStack();pose.last().pose().set(stack.last().pose());pose.last().normal().set(stack.last().normal());
            Minecraft.getInstance().getEntityRenderDispatcher().render(entry.entity,.5,1,.5,dock.yaw,0,pose,buffers,light);
        }catch(RuntimeException|LinkageError failure){if(entry!=null)entry.entity=null;}
    }
    @Override public boolean shouldRenderOffScreen(NativeAircraftDock.Dock dock){return true;}
    @Override public int getViewDistance(){return 160;}
    @Override public AABB getRenderBoundingBox(NativeAircraftDock.Dock dock){return new AABB(dock.getBlockPos()).inflate(16);}
}
