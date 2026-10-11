package dev.starfold;

/** Bounded outward search over authoritative columns; independent of Minecraft for terrain regression tests. */
final class NativeStarterSite {
    record Column(int ground,boolean water,boolean obstructed) {}
    interface Heights { Column at(int x,int z); }
    record Circle(int x,int y,int z,int wet,int nearWater,int trees,int relief,long distance) {
        boolean qualified(){return wet==0&&nearWater==0&&trees==0&&relief<=3;}
    }
    static double landingRadius(int width,int depth){return Math.hypot(width,depth)/2+4;}
    static boolean inside(double x,double z,double radius){return x*x+z*z<=radius*radius;}
    /** Supercover raster: include the block containing every point of the continuous circle. */
    static boolean padCell(int dx,int dz,double radius){return inside(dx,dz,radius+Math.sqrt(.5));}
    static boolean foundationAllowed(int waterDepth){return waterDepth<=2;}
    static boolean containsRectangle(double cx,double cz,double radius,double minX,double minZ,double maxX,double maxZ){
        return inside(minX-cx,minZ-cz,radius)&&inside(minX-cx,maxZ-cz,radius)&&inside(maxX-cx,minZ-cz,radius)&&inside(maxX-cx,maxZ-cz,radius);
    }
    static Circle inspect(int x,int z,double radius,Heights heights){
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,wet=0,near=0,trees=0,n=(int)Math.ceil(radius+8);
        for(int dx=-n;dx<=n;dx++)for(int dz=-n;dz<=n;dz++){
            if(!inside(dx,dz,radius+8))continue;
            var c=heights.at(x+dx,z+dz);
            if(c.water())near++;
            if(!inside(dx,dz,radius))continue;
            min=Math.min(min,c.ground());max=Math.max(max,c.ground());if(c.water())wet++;if(c.obstructed())trees++;
        }
        return new Circle(x,max,z,wet,near,trees,max-min,0);
    }
    static java.util.List<Circle> rank(int x,int z,double radius,int search,Heights heights){
        var all=new java.util.ArrayList<Circle>();
        for(int ring=0;ring<=search;ring+=16)for(int dx=-ring;dx<=ring;dx+=16)for(int dz=-ring;dz<=ring;dz+=16){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=ring||!inside(dx,dz,search))continue;
            var s=inspect(x+dx,z+dz,radius,heights);
            all.add(new Circle(s.x,s.y,s.z,s.wet,s.nearWater,s.trees,s.relief,(long)dx*dx+(long)dz*dz));
        }
        all.sort(order());
        return all;
    }
    static java.util.Comparator<Circle> order(){return java.util.Comparator.comparing((Circle s)->!s.qualified()).thenComparingInt(Circle::wet).thenComparingInt(Circle::nearWater).thenComparingInt(Circle::relief).thenComparingInt(Circle::trees).thenComparingLong(Circle::distance);}
    private NativeStarterSite(){}
}
