#version 150
uniform vec3 EyeBody;
uniform float PlanetRadius, SunTime, Halo;
in vec3 sunPoint, sphereUnit;
in float spaceFacing;
out vec4 fragColor;
void main(){
    if(spaceFacing<0.0) discard;
    vec3 d=sunPoint-EyeBody;float distance=length(d);d/=max(0.000001,distance);
    float b=dot(EyeBody,d),c=dot(EyeBody,EyeBody)-PlanetRadius*PlanetRadius,disc=b*b-c;
    if(disc>=0.0){float root=sqrt(disc),near=-b-root,far=-b+root;if(far>0.0001&&near<distance-0.0001) discard;}
    vec3 n=normalize(sphereUnit);
    if(Halo>0.5){float rim=1.0-abs(dot(n,-d));fragColor=vec4(1.0,0.55,0.18,0.14*rim*rim);return;}
    float cells=0.5+0.5*sin(n.x*48.0+sin(n.y*39.0+SunTime*0.06))*sin(n.z*43.0-SunTime*0.04);
    fragColor=vec4(1.0,mix(0.52,0.88,cells),mix(0.12,0.48,cells),1.0);
}
