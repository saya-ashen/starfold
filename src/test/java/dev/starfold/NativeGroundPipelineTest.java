package dev.starfold;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeGroundPipelineTest {
    // Explicit rounded multiply/add probe, not a measurement of a particular GPU compiler.
    static float recoveredW(float z){float near=.05f,far=43200;float a=-(far+near)/(far-near),b=-2*far*near/(far-near);float clipZ=a*z+b,clipW=-z;return (1/b)*clipZ+(a/b)*clipW;}
    @Test void floatPerspectiveRoundTripCanMoveAFlatSeaByWholeBlocks()throws Exception{
        double worst=0;float depth=0,w=0;
        for(int i=0;i<20000;i++){float z=(float)(-50-i*1.0437),recovered=recoveredW(z);double error=Math.abs(-822f/recovered+822);if(error>worst){worst=error;depth=z;w=recovered;}}
        assertTrue(worst>1,"Perspective depth cancellation must be reproduced");
        var out=Path.of("build/native-pipeline");Files.createDirectories(out);
        Files.writeString(out.resolve("reproduction.json"),String.format(Locale.ROOT,"{\"height\":822,\"near\":0.05,\"far\":43200,\"depth\":%.9f,\"recovered_w\":%.9f,\"old_height_error\":%.9f,\"new_height_error\":0,\"exact_screenshot_reproduced\":false,\"gpu_verified\":false}\n",depth,w,worst));
    }
    @Test void captureRetainsChunkOffsetBeforeProjection(){String s=NativeGroundShaderSource.captureView("void cpCapOriginalMain() {vec3 pos=Position+ChunkOffset;gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);}");assertTrue(s.contains("vec4 cpNativeModelView;"));assertTrue(s.contains("cpNativeModelView=ModelViewMat * vec4(pos, 1.0)"));assertTrue(s.contains("gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);"));}
    @Test void lineCaptureKeepsViewScaleAndOriginalExtrusion(){String s=NativeGroundShaderSource.captureView("void cpNativeOriginalMain() {vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0);vec4 linePosEnd=ProjMat*VIEW_SCALE*ModelViewMat*vec4(Position+Normal,1.0);gl_Position=linePosStart;}");assertTrue(s.contains("cpNativeModelView=VIEW_SCALE * ModelViewMat"));assertTrue(s.contains("vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0);"));assertTrue(s.contains("vec4 linePosEnd=ProjMat*VIEW_SCALE"));assertTrue(NativeGroundShaderSource.VERTEX.contains("gl_Position.xy+=screenOffset*gl_Position.w"));}
    @Test void directViewRecoveryPreservesFlatSeaAcrossAllSourcePlots(){
        var rotation=new org.joml.Matrix4f().rotationXYZ(.67f,-1.12f,.38f);
        var x=new org.joml.Vector3f(1,0,0).mulDirection(rotation);var y=new org.joml.Vector3f(0,1,0).mulDirection(rotation);var z=new org.joml.Vector3f(0,0,1).mulDirection(rotation);
        for(int observer=0;observer<6;observer++)for(int face=0;face<6;face++)for(float height:new float[]{175.9f,822,1088})for(int u:new int[]{0,16,1024,2048}){
            float plot=(face-observer)*4096;var view=new org.joml.Vector3f(plot+u-1024,-height,2048-1024).mulDirection(rotation);
            assertEquals(u,1024+view.dot(x)-plot,.01);assertEquals(0,height+view.dot(y),.01);assertEquals(2048,1024+view.dot(z),.01);
        }
    }
    @Test void upstreamCaptureFailsClosedInsteadOfGuessing(){assertThrows(IllegalArgumentException.class,()->NativeGroundShaderSource.captureView("void cpNativeOriginalMain() {gl_Position=vec4(Position,1);}"));}
    @Test void nativeShaderDoesNotReconstructDepthOrRepeatFarAtlasTexels(){assertFalse(NativeGroundShaderSource.VERTEX.contains("inverse(ProjMat)"));assertFalse(NativeGroundShaderSource.TEXTURE.contains("dFdx"));assertFalse(NativeGroundShaderSource.TEXTURE.contains("fract("));assertTrue(NativeGroundShaderSource.TEXTURE.contains("if(CPNativeLowFrequency>=1.0)return vec4(1.0)"));}
    @Test void bothScreenshotAltitudesUseFullyLowFrequencyCover(){for(double h:new double[]{128,175.9,822,1088})assertEquals(1,NativeGroundPipeline.lowFrequency(h));assertEquals(0,NativeGroundPipeline.lowFrequency(96));assertEquals(.5,NativeGroundPipeline.lowFrequency(112));double previous=0;for(int h=96;h<=128;h++){double v=NativeGroundPipeline.lowFrequency(h);assertTrue(v>=previous);previous=v;}}
    @Test void packedNormalAlbedoHasAtMostOneRgbUnitError(){for(int c=0;c<256;c++){int rgb=c<<16|c<<8|c;for(int shift:new int[]{16,8,0}){float n=NativeGroundPipeline.channel(rgb,shift);float packed=Math.round(n*127)/127f;assertTrue(Math.abs((packed*.5+.5)*255-c)<=1.01);}}}
    @Test void partialInitialCoverIsNotAPublishableGlobe(){Object[] old=new Object[384],next=new Object[384];for(int i=0;i<141;i++)next[i]=new Object();assertFalse(NativeGroundPipeline.publish(243,false,old,next));assertFalse(NativeGroundPipeline.publish(0,false,old,next));Arrays.fill(next,new Object());assertFalse(NativeGroundPipeline.publish(0,true,old,next));assertTrue(NativeGroundPipeline.publish(0,false,old,next));}
    @Test void updatesKeepPreviousCohortAndIdleFramesDoNotRepublish(){Object[] old=new Object[384],next=new Object[384];Arrays.fill(old,new Object());assertFalse(NativeGroundPipeline.publish(0,false,old,next));next[0]=new Object();assertFalse(NativeGroundPipeline.publish(1,false,old,next));assertTrue(NativeGroundPipeline.publish(0,false,old,next));}
    @Test void initialParentCoverUsesBoundedNearBudgetThenReturnsToBackground(){assertEquals(6_000_000,NativeGroundViewPolicy.coverBudget(false));assertEquals(NativeGroundViewPolicy.NEAR_BUDGET_NS,NativeGroundViewPolicy.coverBudget(false));assertEquals(1_000_000,NativeGroundViewPolicy.coverBudget(true));assertEquals(NativeGroundViewPolicy.FRAME_BUDGET_NS/2,NativeGroundViewPolicy.coverBudget(true));}
    @Test void rendererUsesInitialCoverBudgetWithoutPublishingPartialFaces()throws Exception{var s=Files.readString(Path.of("src/main/java/dev/starfold/NativeGroundRenderer.java"));assertTrue(s.contains("boolean parentsReady=NativeGroundPipeline.complete(parents)"));assertTrue(s.contains("NativeGroundViewPolicy.coverBudget(parentsReady)"));assertTrue(s.contains("if(parentJob.done()){var meshes=parentJob.finish();parentJob=null;System.arraycopy(meshes,0,parents,0,6)"));assertTrue(s.contains("!parentsReady||job!=null"));}
    @Test void parentCoverageMustHaveAllSixFaces(){Object[] faces=new Object[6];for(int i=0;i<6;i++){assertFalse(NativeGroundPipeline.complete(faces));faces[i]=new Object();}assertTrue(NativeGroundPipeline.complete(faces));}
    @Test void rendererSeparatesOpaqueFarCoverFromRealWaterAndUsesCompleteParents()throws Exception{String s=Files.readString(Path.of("src/main/java/dev/starfold/NativeGroundRenderer.java"));assertTrue(s.contains("builders.surface?RenderType.solid():layer"));assertTrue(s.contains("buffers.surface?RenderType.solid():layer"));assertTrue(s.contains("NativeGroundPipeline.complete(parents))render"));assertTrue(s.contains("baselineTop(builders[face],face,x,z,16"));assertTrue(s.contains("clearParents()"));assertTrue(s.contains("if(selectionReady()&&ready){for(int p=face*64"));}
}
