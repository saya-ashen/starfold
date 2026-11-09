#version 150
#moj_import <starfold:ground_light.glsl>
uniform vec3 SunBody, BodyEye, Occluder, ShadeCenter, SkyColor, AtmosphereGrade;
uniform float OccluderRadius, ShadeRadius, BodyFade, PatchFade;
uniform int GasCount;
uniform float GasSpheres[256];
in vec4 surfaceColor;
in vec3 bodyPosition, bodyNormal;
in float spaceFacing;
flat in float patchOld, cpSurfaceClass;
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
void main(){
    if(spaceFacing<0.0)discard;
    float fade=PatchFade;if(patchOld>0.5)fade=-1.0-fade;
    if(surfaceColor.a>0.5&&cpGroundHidden(fade))discard;
    if(occulted(BodyEye,bodyPosition,Occluder,OccluderRadius))discard;
    float daylight=cpGroundDaylight(normalize(SunBody-bodyPosition),normalize(bodyPosition),normalize(bodyNormal));
    if(occulted(bodyPosition,SunBody,ShadeCenter,ShadeRadius))daylight=0.0;
    float gasLight=1.0;
    for(int i=0;i<64;i++){
        if(i>=GasCount)break;
        vec3 center=vec3(GasSpheres[i*4],GasSpheres[i*4+1],GasSpheres[i*4+2]);
        gasLight=min(gasLight,gasVisibility(bodyPosition,SunBody,center,GasSpheres[i*4+3]));
    }
    daylight*=gasLight;
    // Alpha 0 marks a self-lit vertex (anomaly spire). An explicit branch: Veil's serializer loses a ternary's grouping.
    float light=0.12+0.88*daylight;
    if(surfaceColor.a<0.5)light=1.0;
    fragColor=vec4(mix(SkyColor,surfaceColor.rgb*light*AtmosphereGrade,BodyFade),1.0);
}
