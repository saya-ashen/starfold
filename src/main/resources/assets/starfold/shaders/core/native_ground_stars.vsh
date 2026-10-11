#version 150
in vec3 Position;
in vec4 Color;
uniform mat4 ModelViewMat, ProjMat;
uniform vec3 SpaceLens;
out float spaceFacing;
out vec4 vertexColor;
vec4 spaceProject(vec4 view) {
    vec4 ordinary=ProjMat*view;
    spaceFacing=-view.z;
    if(SpaceLens.x<=0.0) return ordinary;
    float r=max(0.000001,length(view.xyz));
    // Bound the lens chart by its screen-corner ray, not by an arbitrary hemisphere.
    // Rear antipodal triangles must never wrap across the finite front viewport.
    vec2 corner=1.0/SpaceLens.yz;
    float rho2=dot(corner,corner);
    float cosine=(4.0-rho2)/(4.0+rho2);
    spaceFacing=-view.z-r*cosine;
    float d=max(r*(1.0+cosine),r-view.z);
    // Radial depth remains monotonic along each ray, including off-axis rays.
    vec4 stereo=vec4(2.0*SpaceLens.yz*view.xy*r/d,-ProjMat[2][2]*r+ProjMat[3][2],r);
    return mix(ordinary,stereo,SpaceLens.x);
}
void main() {
    gl_Position=spaceProject(ModelViewMat*vec4(Position,1));
    gl_Position.z=gl_Position.w*(1.0-0.000001);
    vertexColor=Color;
}
