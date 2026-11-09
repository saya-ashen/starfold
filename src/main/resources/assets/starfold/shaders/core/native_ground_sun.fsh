#version 150
uniform vec3 EyeBody, SunBody;
uniform float PlanetRadius, SunRadius, SunTime, Halo, HaloWidth, HaloStrength;
uniform vec3 HaloColor, SurfaceLow, SurfaceHigh;
in vec3 sunPoint, sphereUnit;
in float spaceFacing;
out vec4 fragColor;
float hash(vec3 p){return fract(sin(dot(p,vec3(127.1,311.7,74.7)))*43758.5453);}
void main(){
    if(spaceFacing<0.0) discard;
    vec3 d=sunPoint-EyeBody;float distance=length(d);d/=max(0.000001,distance);
    float b=dot(EyeBody,d),c=dot(EyeBody,EyeBody)-PlanetRadius*PlanetRadius,disc=b*b-c;
    if(disc>=0.0){float root=sqrt(disc),near=-b-root,far=-b+root;if(far>0.0001&&near<distance-0.0001) discard;}
    vec3 n=normalize(sphereUnit);
    if(Halo>0.5){float radial=sqrt(max(0.0,1.0-pow(dot(n,-d),2.0)));float edge=max(0.0,1.0-radial*radial);float glow=edge*edge;fragColor=vec4(HaloColor,HaloStrength*glow);return;}
    vec3 a=abs(n),cube=n/max(a.x,max(a.y,a.z));
    vec3 texel=(cube*0.5+0.5)*24.0;
    // Differentiate the continuous cube, not face UVs: no false minification at face seams.
    vec3 footprint=fwidth(texel);
    float detail=1.0-smoothstep(0.5,1.5,max(footprint.x,max(footprint.y,footprint.z)));
    vec3 cell=min(floor(texel),vec3(23.0));
    vec3 q=(cell+0.5)/24.0*2.0-1.0;
    if(a.x>=a.y&&a.x>=a.z)q.x=sign(n.x);
    else if(a.y>=a.z)q.y=sign(n.y);
    else q.z=sign(n.z);
    q=normalize(q);
    float tick=floor(SunTime*0.25+hash(cell)*4.0);
    float grain=0.65*hash(cell+tick)+0.35*hash(floor(q*7.0));
    float cells=floor(grain*4.0)/3.0;
    vec3 view=normalize(EyeBody-SunBody-q*SunRadius);
    float limb=0.82+0.18*floor(clamp(dot(q,view),0.0,1.0)*3.0+0.5)/3.0;
    vec3 color=mix(SurfaceLow,SurfaceHigh,cells)*limb;
    fragColor=vec4(mix(mix(SurfaceLow,SurfaceHigh,0.5)*0.94,color,detail),1.0);
}
