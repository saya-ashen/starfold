#version 150
uniform vec3 SunBody, BodyEye, Occluder, ShadeCenter, SkyColor;
uniform float OccluderRadius, ShadeRadius, BodyFade;
in vec4 surfaceColor;
in vec3 bodyPosition, bodyNormal;
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
void main(){
    if(spaceFacing<0.0)discard;
    if(occulted(BodyEye,bodyPosition,Occluder,OccluderRadius))discard;
    float daylight=max(0.0,dot(normalize(SunBody-bodyPosition),normalize(bodyNormal)));
    if(occulted(bodyPosition,SunBody,ShadeCenter,ShadeRadius))daylight=0.0;
    // Alpha 0 marks a self-lit vertex (anomaly spire). An explicit branch: Veil's serializer loses a ternary's grouping.
    float light=0.12+0.88*daylight;
    if(surfaceColor.a<0.5)light=1.0;
    fragColor=vec4(mix(SkyColor,surfaceColor.rgb*light,BodyFade),1.0);
}
