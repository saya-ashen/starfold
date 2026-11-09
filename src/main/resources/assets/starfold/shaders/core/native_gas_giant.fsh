#version 150
uniform vec3 EyeBody, SunBody, HomeCenter, SurfaceLow, SurfaceHigh, GasFogColor, SkyColor, AtmosphereGrade;
uniform float HomeRadius, GasPhase, BandFrequency, BandWarp, Ambient, BodyFade, Inside;
uniform int GasCount;
uniform float GasSpheres[256];
in vec3 gasPoint, sphereUnit;
in float spaceFacing;
out vec4 fragColor;
bool occulted(vec3 eye,vec3 target,vec3 center,float radius){
    if(radius<=0.0)return false;
    vec3 d=target-eye;float distance=length(d);d/=max(0.000001,distance);
    vec3 o=eye-center;float b=dot(o,d),c=dot(o,o)-radius*radius,disc=b*b-c;
    if(disc<0.0)return false;
    float root=sqrt(disc),near=-b-root,far=-b+root;
    return far>0.0001&&near<distance-0.0001;
}
float gasVisibility(vec3 eye,vec3 sun,vec3 center,float radius){
    vec3 offset=center-eye,ray=sun-eye;
    float distance=length(ray);
    if(distance<0.000000001)return 1.0;
    if(length(offset)<=radius)return 0.0;
    ray/=distance;
    float along=dot(offset,ray);
    if(along<=0.0||along>=distance)return 1.0;
    return smoothstep(radius*0.95,radius*1.1,length(offset-ray*along));
}
float hash(vec3 p){return fract(sin(dot(p,vec3(127.1,311.7,74.7)))*43758.5453);}
void main(){
    if(spaceFacing<0.0)discard;
    if(Inside>0.5){fragColor=vec4(GasFogColor,1.0);return;}
    if(occulted(EyeBody,gasPoint,HomeCenter,HomeRadius))discard;
    vec3 n=normalize(sphereUnit);
    vec3 a=abs(n),cube=n/max(a.x,max(a.y,a.z));
    vec3 texel=(cube*0.5+0.5)*40.0;
    // Continuous cube derivatives also keep detail stable across the six face boundaries.
    vec3 footprint=fwidth(texel);
    float detail=1.0-smoothstep(0.5,1.5,max(footprint.x,max(footprint.y,footprint.z)));
    vec3 cell=min(floor(texel),vec3(39.0));
    vec3 q=(cell+0.5)/40.0*2.0-1.0;
    if(a.x>=a.y&&a.x>=a.z)q.x=sign(n.x);
    else if(a.y>=a.z)q.y=sign(n.y);
    else q.z=sign(n.z);
    q=normalize(q);
    float noise=hash(cell+GasPhase);
    float warp=BandWarp*(q.x*sin(GasPhase)+q.z*cos(GasPhase)+0.35*(noise-0.5));
    float bands=0.5+0.5*sin(q.y*BandFrequency+warp+GasPhase);
    bands=floor(clamp(bands+0.08*(noise-0.5),0.0,0.9999)*5.0)/4.0;
    bands=mix(0.5,bands,detail);
    vec3 toSun=SunBody-gasPoint;
    float daylight=max(0.0,dot(n,toSun/max(0.000001,length(toSun))));
    float gasLight=1.0;
    for(int i=0;i<64;i++){
        if(i>=GasCount)break;
        vec3 center=vec3(GasSpheres[i*4],GasSpheres[i*4+1],GasSpheres[i*4+2]);
        gasLight=min(gasLight,gasVisibility(gasPoint,SunBody,center,GasSpheres[i*4+3]));
    }
    vec3 color=mix(SurfaceLow,SurfaceHigh,bands)*(Ambient+(1.0-Ambient)*daylight*gasLight)*AtmosphereGrade;
    fragColor=vec4(mix(SkyColor,color,BodyFade),1.0);
}
