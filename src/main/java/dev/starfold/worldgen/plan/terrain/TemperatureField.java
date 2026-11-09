package dev.starfold.worldgen.plan.terrain;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

/** Two-channel, node-resolution climate atlas with a two-node canonical halo on each face. */
public final class TemperatureField {
    private final TerrainPlan terrain;
    private final int width;
    private final float[] texels;
    public TemperatureField(TerrainPlan terrain) {
        this.terrain=terrain;
        width=terrain.size()/16+5;
        var altitude=new float[terrain.nodeCount()];
        for(int node=0;node<altitude.length;node++) {
            var c=terrain.chart(terrain.position(node));
            altitude[node]=(float)(terrain.height(c.face(),c.u(),c.v())-128);
        }
        texels=new float[width*width*6*2];
        for(var face:Face.VALUES)for(int u=0;u<width;u++)for(int v=0;v<width;v++) {
            int node=terrain.node(face,u-2,v-2),offset=index(face,u,v);
            texels[offset]=terrain.field(TerrainPlan.TEMPERATURE,node);
            texels[offset+1]=altitude[node];
        }
    }
    public int width() { return width; }
    public int height() { return width*6; }
    public float[] texels() { return texels.clone(); }
    private int index(Face f,int u,int v) { return ((f.ordinal()*width+v)*width+u)*2; }
    public double temperature(Point up,double altitude) {
        var c=terrain.chart(up);
        return sample(c.face(),c.u(),c.v(),0)-terrain.recipe().value("climate","lapse_rate")
            *(altitude-sample(c.face(),c.u(),c.v(),1));
    }
    public double sample(Face face,double u,double v,int channel) {
        double x=u/16,z=v/16;int a=(int)Math.floor(x),b=(int)Math.floor(z);
        double t=x-a,s=z-b;
        return cubic(row(face,a,b-1,t,channel),row(face,a,b,t,channel),
            row(face,a,b+1,t,channel),row(face,a,b+2,t,channel),s);
    }
    private double row(Face face,int a,int b,double t,int channel) {
        return cubic(texels[index(face,a+1,b+2)+channel],texels[index(face,a+2,b+2)+channel],
            texels[index(face,a+3,b+2)+channel],texels[index(face,a+4,b+2)+channel],t);
    }
    private static double cubic(double a,double b,double c,double d,double t) {
        return b+.5*t*(c-a+t*(2*a-5*b+4*c-d+t*(3*(b-c)+d-a)));
    }
}
