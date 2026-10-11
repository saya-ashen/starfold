package dev.starfold;

import dev.starfold.PlanetSpace.Side;

/** Same albedo/tint and linear-light convention used by the cubic shader at every distance. */
public final class CubicColor {
    public static double linear(double s){return s<=.04045?s/12.92:Math.pow((s+.055)/1.055,2.4);}
    public static double encoded(double c){return c<=.0031308?12.92*c:1.055*Math.pow(c,1/2.4)-.055;}
    public static double[] tint(int material,Side side){return material==22&&side==Side.UP?new double[]{127/255.0,179/255.0,79/255.0}:new double[]{1,1,1};}
    /** NativeImage pixels are ABGR. Average all visible texture pixels, after the identical tint. */
    public static float[] average(int[] abgr,int material,Side side){double[] sum=new double[3],t=tint(material,side);double weight=0;for(int pixel:abgr){double a=(pixel>>>24)/255.0;weight+=a;for(int j=0;j<3;j++)sum[j]+=a*linear((pixel>>(8*j)&255)/255.0*t[j]);}if(weight==0)return new float[]{1,0,1};return new float[]{(float)(sum[0]/weight),(float)(sum[1]/weight),(float)(sum[2]/weight)};}
    public static double textureWeight(double footprint){double t=Math.clamp((footprint-.5)/1.5,0,1);return 1-t*t*(3-2*t);}
    private CubicColor(){}
}
