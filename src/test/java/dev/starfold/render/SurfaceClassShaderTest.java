package dev.starfold.render;

import com.mojang.blaze3d.vertex.*;
import dev.starfold.api.SurfaceClass;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceClassShaderTest {
    @Test void sectionConsumerWritesClassAndPreservesOtherVertexFields() {
        try (var memory=new ByteBufferBuilder(256)) {
            var builder=new BufferBuilder(memory,VertexFormat.Mode.QUADS,DefaultVertexFormat.BLOCK);
            for(int c=0;c<=4;c++) {
                var out=new SurfaceClassVertices(builder,c);
                for(int i=0;i<4;i++) out.addVertex(c,2,3).setColor(32,64,96,255).setUv(.2f,.3f).setUv2(240,240).setNormal(0,1,0);
            }
            try(var mesh=builder.buildOrThrow()) {
                var data=mesh.vertexBuffer();
                for(int c=0;c<=4;c++) {
                    assertEquals(SurfaceClass.alpha(c),Byte.toUnsignedInt(data.get(c*128+15)));
                    assertEquals(32,Byte.toUnsignedInt(data.get(c*128+12)));
                    assertEquals(.3f,data.getFloat(c*128+20));
                }
            }
        }
    }
    @Test void restoreInputsBeforeOriginalMainAndPreserveFarTagNamespace() throws Exception {
        for(String program:new String[]{"rendertype_solid","rendertype_cutout","rendertype_cutout_mipped"}) {
            var v=CoreShaderSource.patch(program,true,VoxelFaceShaderTest.resource("core/"+program+".vsh"));
            assertTrue(v.contains("flat out float cpSurfaceClass;"));
            assertTrue(v.indexOf("cpSurfaceColor.a=1.0;")<v.lastIndexOf("cpNativeOriginalMain();"));
            assertTrue(v.contains("vertexColor = cpSurfaceColor"));
            assertTrue(v.contains("cpSurfaceUV.y-=cpSurfaceClass*2.0"));
            assertTrue(v.contains("tag=round(cpSurfaceColor.a*255.0)"));
        }
        var v=CoreShaderSource.patch("rendertype_translucent",true,VoxelFaceShaderTest.resource("core/rendertype_translucent.vsh"));
        assertFalse(v.contains("cpSurfaceColor.a=1.0"));
        var source=GroundFarTextureTest.expand(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/assets/starfold/shaders/core/ground_compact.vsh")));
        v=CoreShaderSource.patch(GroundCompact.NAME,true,source);
        assertTrue(v.indexOf("Color=vec4(cpMaterial")<v.indexOf("cpSurfaceColor=Color;"));
        assertTrue(v.indexOf("cpSurfaceColor=Color;")<v.lastIndexOf("cpNativeOriginalMain();"));
    }
}
