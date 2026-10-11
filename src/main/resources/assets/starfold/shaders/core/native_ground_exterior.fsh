#version 150
uniform vec3 SunBody;
in vec4 surfaceColor;
in vec3 bodyPosition, bodyNormal;
in float spaceFacing;
out vec4 fragColor;
void main(){
    if(spaceFacing<0.0)discard;
    float daylight=max(0.0,dot(normalize(SunBody-bodyPosition),normalize(bodyNormal)));
    // Alpha 0 marks a self-lit vertex (anomaly spire). An explicit branch: Veil's serializer loses a ternary's grouping.
    float light=0.12+0.88*daylight;
    if(surfaceColor.a<0.5)light=1.0;
    fragColor=vec4(surfaceColor.rgb*light,1.0);
}
