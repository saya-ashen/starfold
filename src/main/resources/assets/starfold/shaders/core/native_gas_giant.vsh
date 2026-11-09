#version 150
in vec3 Position;
uniform mat4 ModelViewMat, ProjMat;
uniform vec3 SpaceLens, DrawCenter, ChartU, ChartUp, ChartV;
uniform float GasRadius;
out vec3 gasPoint, sphereUnit;
out float spaceFacing;
vec4 spaceProject(vec4 view){
    vec4 ordinary=ProjMat*view;
    spaceFacing=-view.z;
    if(SpaceLens.x<=0.0)return ordinary;
    float r=max(0.000001,length(view.xyz));
    vec2 corner=1.0/SpaceLens.yz;
    float rho2=dot(corner,corner),cosine=(4.0-rho2)/(4.0+rho2);
    spaceFacing=-view.z-r*cosine;
    float d=max(r*(1.0+cosine),r-view.z);
    return mix(ordinary,vec4(2.0*SpaceLens.yz*view.xy*r/d,-ProjMat[2][2]*r+ProjMat[3][2],r),SpaceLens.x);
}
void main(){
    vec3 chart=vec3(dot(Position,ChartU),dot(Position,ChartUp),dot(Position,ChartV));
    gl_Position=spaceProject(ModelViewMat*vec4(DrawCenter+chart*GasRadius,1.0));
    gasPoint=Position*GasRadius;
    sphereUnit=Position;
}
