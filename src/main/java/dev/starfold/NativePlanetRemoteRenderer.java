package dev.starfold;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import java.util.*;

/** Every other planet of the system, each derived from its saved generator and actual indexed column surfaces. */
public final class NativePlanetRemoteRenderer {
    private static final Map<Integer,Body> bodies=new HashMap<>();private static Object level;private static int turn;private static long clock;private static int regionDrawn;
    /** Client ticks without word of a planet before it is dropped; the server speaks of each every second. */
    private static final int SILENCE=200;private static long ownTold=Long.MIN_VALUE/2;
    /** One distant planet: what is known of it, the meshes being built and the ones drawn. */
    private static final class Body {
        NativePlanetRemoteView view=new NativePlanetRemoteView();GroundSpace.Config mask;
        NativePlanetRemoteView.Snapshot building,resident,fallback;Job job;int cursor;long heard;
        final VertexBuffer[] meshes=new VertexBuffer[384],staging=new VertexBuffer[384],fallbackMeshes=new VertexBuffer[384];
        void session(long session){if(view.session()!=0&&view.session()!=session){if(job!=null)job.close();job=null;for(int i=0;i<384;i++){if(staging[i]!=null)staging[i].close();staging[i]=null;if(resident!=null){if(fallbackMeshes[i]!=null)fallbackMeshes[i].close();fallbackMeshes[i]=meshes[i];meshes[i]=null;}}if(resident!=null)fallback=resident;building=resident=null;cursor=0;mask=null;view=new NativePlanetRemoteView();}}
        void close(){if(job!=null)job.close();job=null;for(int i=0;i<384;i++){if(meshes[i]!=null)meshes[i].close();if(staging[i]!=null)staging[i].close();if(fallbackMeshes[i]!=null)fallbackMeshes[i].close();meshes[i]=staging[i]=fallbackMeshes[i]=null;}building=resident=fallback=null;cursor=0;}
        void work(long deadline){if(mask==null)return;if(building==null&&(resident==null||resident.generation()!=view.generation())){building=view.snapshot();cursor=0;}if(building==null)return;while(cursor<384&&System.nanoTime()<deadline){if(!NativeGroundViewPolicy.exists(building.terrain().shape(),cursor)){cursor++;continue;}if(job==null)job=new Job(building,cursor,mask);job.step(deadline);if(job.tile<256)break;staging[cursor]=job.finish();job.close();job=null;cursor++;}if(cursor==384){for(int i=0;i<384;i++){if(meshes[i]!=null)meshes[i].close();meshes[i]=staging[i];staging[i]=null;}resident=building;building=null;}}
    }
    public static void initialize(){net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent)->{var mc=net.minecraft.client.Minecraft.getInstance();if(level!=null&&level!=mc.level)clear();clock++;
        // A planet the server no longer tells of has fallen out of the nearest: its meshes are given up.
        // The planet arrived at is told of no longer; what it was drawn with is kept until its ground is shown.
        bodies.entrySet().removeIf(e->{var body=e.getValue();if(clock-body.heard<=SILENCE||NativeSpaceClient.frame()==null&&e.getKey()==NativeGroundClient.planet()&&!NativeGroundRenderer.selectionReady())return false;body.close();return true;});});LocalNet.clientReceiver(NativePlanetRemotePackets.Summary.TYPE,(p,c)->{if(!accepts(p.profile().session(),p.planet()))return;identity(net.minecraft.client.Minecraft.getInstance().level,p.profile().session());var body=bodies.computeIfAbsent(p.planet(),k->new Body());body.heard=clock;var mask=mask(p.profile().session());if(mask!=null)body.mask=mask;body.view.summary(p);});LocalNet.clientReceiver(NativePlanetRemotePackets.Surface.TYPE,(p,c)->{var body=bodies.get(p.planet());if(body!=null&&accepts(p.surface().session(),p.planet())&&net.minecraft.client.Minecraft.getInstance().level==level)body.view.surface(p);});}
    private static void identity(Object current,long session){if(level!=current){clear();level=current;}else for(var body:bodies.values())body.session(session);}
    public static void clear(){for(var body:bodies.values())body.close();bodies.clear();level=null;regionDrawn=0;}
    /** Dev probe only: how many of the other planets heard of have a mesh to be drawn with. */
    public static String probeReading(){int shown=0;for(var body:bodies.values()){var drawn=body.resident!=null?body.resident:body.fallback;if(drawn!=null&&drawn.planet()!=NativeGroundClient.planet())shown++;}return "remote "+shown+" of "+bodies.size()+" own "+(bodies.containsKey(NativeGroundClient.planet())?bodies.get(NativeGroundClient.planet()).resident!=null?"built":"building":"none");}
    private static long key(NativePlanetShape shape,Face f,int x,int z){return NativeGroundOwnership.key(shape,f.ordinal(),x,z);}
    private static final class Job implements AutoCloseable {
        final ByteBufferBuilder memory=new ByteBufferBuilder(256*1024);final BufferBuilder out=new BufferBuilder(memory,VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.BLOCK);
        final NativePlanetRemoteView.Snapshot snapshot;final NativePlanetHeightCache heights;final GroundSpace.Config mask;final int patch,face,x0,z0;int tile,pixel;
        final NativePlanetShape shape;final net.minecraft.world.level.BlockAndTintGetter tint;
        final NativeGroundExteriorMesh.Heights field=new NativeGroundExteriorMesh.Heights(){public double node(Face f,int x,int z){double h=heights.surfaceNode(f,x,z);return masked(x,z)?Math.max(h,mask.height()):h;}public Double edited(Face f,int x,int z){var cell=shape.topology().canonical(new CubeSurfaceTopology.Cell(f,x,0,z)).cell();var s=snapshot.surfaces().get(key(shape,cell.face(),cell.u(),cell.v()));return s==null?null:s.height(cell.u()&15,cell.v()&15)+1-128.0;}};
        Job(NativePlanetRemoteView.Snapshot snapshot,int patch,GroundSpace.Config mask){this.mask=Objects.requireNonNull(mask);this.snapshot=snapshot;this.patch=patch;var level=Minecraft.getInstance().level;tint=NativePlanetTint.view(level,level.registryAccess(),snapshot.planet(),snapshot.terrain(),snapshot.biome());heights=new NativePlanetHeightCache(snapshot.terrain());shape=heights.shape();face=NativeGroundViewPolicy.face(patch);x0=NativeGroundViewPolicy.x(patch);z0=NativeGroundViewPolicy.z(patch);}
        boolean masked(int x,int z){return mask.core()>0&&Math.min(x,shape.size()-x)<mask.core()&&Math.min(z,shape.size()-z)<mask.core();}
        boolean refined(int x,int z){for(int a=-16;a<=16;a+=16)for(int b=-16;b<=16;b+=16)if(snapshot.surfaces().containsKey(key(shape,Face.values()[face],x+a,z+b)))return true;return false;}
        int color(int x,int z){long k=key(shape,Face.values()[face],x,z);var surface=snapshot.surfaces().get(k);var state=surface==null?heights.surfaceState(Face.values()[face],x,z):surface.state(x&15,z&15);if(masked(x,z))state=NativeAnomaly.shell();int y=surface==null?heights.surfaceY(Face.values()[face],x,z):surface.height(x&15,z&15);return NativeGroundAlbedo.color(state,NativePlanetTint.position(snapshot.planet(),face,x,y,z),tint);}
        void step(long deadline){var f=Face.values()[face];while(tile<256&&System.nanoTime()<deadline){int x=x0+tile%16*16,z=z0+tile/16*16;if(refined(x,z)){int a=x+(pixel&15),b=z+(pixel>>4);var p=NativeGroundExteriorMesh.node(shape,field,f,a,b);var q=NativeGroundExteriorMesh.node(shape,field,f,a,b+1);var r=NativeGroundExteriorMesh.node(shape,field,f,a+1,b+1);var s=NativeGroundExteriorMesh.node(shape,field,f,a+1,b);var centre=NativeGroundExteriorMesh.centre(shape,field,f,a,b);int rgb=color(a,b);triangle(p,q,centre,rgb);triangle(q,r,centre,rgb);triangle(r,s,centre,rgb);triangle(s,p,centre,rgb);if(++pixel==256){pixel=0;tile++;}}else{var p=NativeGroundExteriorMesh.baseline(shape,field,f,x,z);var q=NativeGroundExteriorMesh.baseline(shape,field,f,x,z+16);var r=NativeGroundExteriorMesh.baseline(shape,field,f,x+16,z+16);var s=NativeGroundExteriorMesh.baseline(shape,field,f,x+16,z);int rgb=color(x+8,z+8);triangle(p,q,r,rgb);triangle(p,r,s,rgb);tile++;}}}
        void triangle(Point a,Point b,Point c,int rgb){var normal=PlanetSpace.unit(b.sub(a).cross(c.sub(a)));if(normal.dot(a)<0)normal=normal.scale(-1);for(var p:List.of(a,b,c))out.addVertex((float)p.x(),(float)p.y(),(float)p.z()).setColor(rgb>>16&255,rgb>>8&255,rgb&255,255).setUv(0,0).setUv2(240,240).setNormal((float)normal.x(),(float)normal.y(),(float)normal.z());}
        void glow(Point[] t){for(var p:t){var n=PlanetSpace.unit(p);out.addVertex((float)p.x(),(float)p.y(),(float)p.z()).setColor(NativeAnomaly.GLOW_TINT>>16&255,NativeAnomaly.GLOW_TINT>>8&255,NativeAnomaly.GLOW_TINT&255,0).setUv(0,0).setUv2(240,240).setNormal((float)n.x(),(float)n.y(),(float)n.z());}}
        VertexBuffer finish(){if(mask.core()>0)for(var t:NativeGroundExteriorMesh.anomalyBeams(shape,Face.values()[face],x0,z0,mask.height()))glow(t);var gpu=new VertexBuffer(VertexBuffer.Usage.STATIC);gpu.bind();try{gpu.upload(out.build());return gpu;}catch(RuntimeException e){gpu.close();throw e;}finally{VertexBuffer.unbind();}}
        public void close(){memory.close();}
    }
    /** A fixed share of each frame builds meshes; the planets take turns at being first to it. */
    private static void work(){long deadline=System.nanoTime()+2_000_000;var order=new ArrayList<>(bodies.values());if(order.isEmpty())return;turn=(turn+1)%order.size();for(int i=0;i<order.size();i++){var body=order.get((turn+i)%order.size());if(body.view.session()!=0)body.work(deadline);}}
    private static void vector(ShaderInstance shader,String name,Point p){shader.getUniform(name).set((float)p.x(),(float)p.y(),(float)p.z());}
    public static void draw(LocalRender c){if(NativeSpaceClient.active()){drawRegion(c);return;}if(!NativeGroundClient.ready())return;identity(Minecraft.getInstance().level,NativeGroundFlightClient.ready()?NativeGroundFlightClient.session():NativeSpaceClient.retainedSession());
        // A vessel high above its planet may leave it for another: the planet is meshed as another planet would be while it is still the vessel's own,
        // from what the client holds of it, so that it is there to be seen the moment it is left.
        if(NativeGroundClient.eye().y()>=GroundOrbit.END&&NativeGroundViewClient.coverageReady()&&NativeGroundViewClient.profile()!=null&&NativeGroundViewClient.profile().session()==NativeGroundFlightClient.session()){
            var body=bodies.computeIfAbsent(NativeGroundClient.planet(),k->new Body());body.heard=clock;body.mask=mask(NativeGroundViewClient.profile().session());if(clock-ownTold>=100||body.view.session()==0){ownTold=clock;body.view.own(NativeGroundViewClient.profile(),NativeGroundClient.planet(),NativeGroundViewClient.changed(),NativeGroundViewClient.surfaces());}
        }
        work();var shader=NativeGroundExterior.shader();if(shader==null||bodies.isEmpty())return;
        var previous=RenderSystem.getShader();boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND),write=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try{RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableCull();RenderSystem.disableBlend();RenderSystem.setShader(()->shader);for(var body:bodies.values()){
            // A planet is drawn with the meshes it had until its next are built: the one arrived at until the ground is shown, any other until then.
            if(body.fallback!=null&&(body.fallback.planet()==NativeGroundClient.planet()?!NativeGroundRenderer.selectionReady():body.resident==null))probeBody(c,shader,body.fallback,body.fallbackMeshes);if(body.resident!=null&&(body.resident.planet()!=NativeGroundClient.planet()||body.fallback==null&&!NativeGroundRenderer.selectionReady()))probeBody(c,shader,body.resident,body.meshes);}
        }finally{RenderSystem.depthMask(write);if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();if(previous!=null)RenderSystem.setShader(()->previous);}
    }
    /** A mask belongs to the view's session; retained bodies keep theirs after that observer has gone. */
    private static GroundSpace.Config mask(long session){if(session!=0&&session==NativeSpaceClient.session())return NativeSpaceClient.mask();return session!=0&&NativeGroundFlightClient.ready()&&session==NativeGroundFlightClient.session()?NativeGroundFlightClient.mask():null;}
    private static void probeBody(LocalRender c,ShaderInstance shader,NativePlanetRemoteView.Snapshot snapshot,VertexBuffer[] buffers){if(NativeArrivalProbe.ENABLED&&snapshot.planet()==NativeGroundClient.planet()&&Arrays.stream(buffers).anyMatch(Objects::nonNull))NativeArrivalClientProbe.remote=true;drawBody(c,shader,snapshot,buffers);}
    private static void drawBody(LocalRender c,ShaderInstance shader,NativePlanetRemoteView.Snapshot snapshot,VertexBuffer[] buffers){var home=NativeGroundClient.home();var target=NativeGroundClient.model().at(snapshot.planet(),NativeGroundClient.time());var eye=NativeGroundClient.eye();var orbit=NativeGroundClient.orbit();var f=orbit.observerFrame;var offset=home.local(target.center());
            vector(shader,"ChartU",target.inverseVector(home.vector(f.u())));vector(shader,"ChartUp",target.inverseVector(home.vector(f.up())));vector(shader,"ChartV",target.inverseVector(home.vector(f.v())));vector(shader,"DrawCenter",new Point(eye.x(),-NativeGroundClient.shape().radius(),eye.z()).add(orbit.offset).add(new Point(offset.dot(f.u()),offset.dot(f.up()),offset.dot(f.v()))));vector(shader,"SunBody",target.local(NativeGroundClient.model().star(target.center()).position()));NativeGroundClient.lensUniforms(shader);
            var matrix=NativeGroundClient.place(new Matrix4f(RenderSystem.getModelViewMatrix()).mul(c.matrixStack().last().pose()),NativeGroundClient.face(),c.camera().getPosition());for(var gpu:buffers){if(gpu==null)continue;gpu.bind();try{gpu.drawWithShader(matrix,RenderSystem.getProjectionMatrix(),shader);}finally{VertexBuffer.unbind();}}
    }
    private static boolean accepts(long session,int planet){if(NativeSpaceClient.present())return session!=0&&session==NativeSpaceClient.session();if(NativeGroundClient.ready()&&planet==NativeGroundClient.planet()&&!NativeGroundRenderer.selectionReady()&&session!=0&&session==NativeSpaceClient.retainedSession())return true;return NativeGroundFlightClient.ready()&&session==NativeGroundFlightClient.session()&&planet!=NativeGroundClient.planet();}
    /** Keep the exact chart exterior, including edits, until the passive stream replaces it. */
    static void departing(){
        var profile=NativeGroundViewClient.profile();int planet=NativeGroundViewClient.cachedPlanet();if(profile==null||planet<0)return;
        var shape=new NativePlanetTerrain(profile.seed(),profile.version(),profile.generation()).shape();var meshes=NativeGroundExterior.take(shape);if(meshes==null)return;
        identity(Minecraft.getInstance().level,profile.session());var body=bodies.computeIfAbsent(planet,k->new Body());body.close();body.mask=mask(profile.session());body.view.own(profile,planet,NativeGroundViewClient.changed(),NativeGroundViewClient.surfaces());body.resident=body.view.snapshot();body.heard=clock;System.arraycopy(meshes,0,body.meshes,0,meshes.length);
    }
    /** The passive summary already carries the own-view profile, coverage and bounded surface stream. */
    static void seedOwn(int planet,long session){var body=bodies.get(planet);if(level!=Minecraft.getInstance().level||body==null||body.view.session()==0||body.view.session()!=NativeSpaceClient.retainedSession())return;var snapshot=body.view.snapshot();if(snapshot!=null)NativeGroundViewClient.preview(session,snapshot);}
    public static void beginFrame(){regionDrawn=0;}
    public static int regionDrawn(){return regionDrawn;}
    /** Ordinary camera-relative Level geometry. The ship never enters this mesh path. */
    private static void drawRegion(LocalRender c){
        long session=NativeSpaceClient.session();if(session==0)return;identity(Minecraft.getInstance().level,session);work();var shader=NativeGroundExterior.shader();if(shader==null)return;
        var previous=RenderSystem.getShader();boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND),write=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        var eye=NativeSpaceClient.universeEye(c.camera().getPosition());var frame=NativeSpaceClient.frame();var model=NativeGroundClient.ephemeris();double tick=NativeSpaceClient.time();var matrix=new Matrix4f(c.positionMatrix());
        try{RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableCull();RenderSystem.disableBlend();RenderSystem.setShader(()->shader);NativeSpaceClient.lens(shader);
            for(var body:bodies.values()){var snapshot=body.resident!=null?body.resident:body.fallback;if(snapshot==null||snapshot.planet()>=model.count())continue;
                var target=model.at(snapshot.planet(),tick);var placement=NativeSpaceView.place(frame,eye,target);double radius=snapshot.terrain().shape().radius()+Math.max(512,NativeSpaceClient.mask().height()+NativeAnomaly.BEAM_HEIGHT);if(!NativeSpaceView.visible(placement.center().length(),radius))continue;
                vector(shader,"ChartU",placement.u());vector(shader,"ChartUp",placement.up());vector(shader,"ChartV",placement.v());vector(shader,"DrawCenter",placement.center());vector(shader,"SunBody",target.local(model.star(target.center()).position()));
                boolean drawn=false;for(var gpu:body.resident!=null?body.meshes:body.fallbackMeshes){if(gpu==null)continue;gpu.bind();try{gpu.drawWithShader(matrix,RenderSystem.getProjectionMatrix(),shader);drawn=true;}finally{VertexBuffer.unbind();}}if(drawn){regionDrawn++;if(NativeArrivalProbe.ENABLED&&snapshot.planet()==NativeSpace.nearest(model,eye,tick))NativeArrivalClientProbe.remote=true;}
            }
        }finally{RenderSystem.depthMask(write);if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();if(previous!=null)RenderSystem.setShader(()->previous);}
    }
    private NativePlanetRemoteRenderer(){}
}
