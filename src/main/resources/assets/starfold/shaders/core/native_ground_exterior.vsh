#version 150
in vec3 Position;
in vec4 Color;
in vec2 Normal;
uniform mat4 ModelViewMat, ProjMat;
uniform vec3 SpaceLens, DrawCenter, ChartU, ChartUp, ChartV;
out vec4 surfaceColor;
out vec3 bodyPosition, bodyNormal;
out float spaceFacing;
flat out float patchOld, cpSurfaceClass;
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
    vec3 position=Position/8.0;
    vec3 normal=vec3(Normal,1.0-abs(Normal.x)-abs(Normal.y));
    if(normal.z<0.0)normal.xy=(1.0-abs(normal.yx))*vec2(normal.x>=0.0?1.0:-1.0,normal.y>=0.0?1.0:-1.0);
    vec3 chart=vec3(dot(position,ChartU),dot(position,ChartUp),dot(position,ChartV));
    gl_Position=spaceProject(ModelViewMat*vec4(DrawCenter+chart,1.0));
    surfaceColor=Color;bodyPosition=position;bodyNormal=normalize(normal);
    float tag=round(Color.a*255.0);
    patchOld=0.0;cpSurfaceClass=0.0;
    if(tag>=128.0&&tag<=137.0){patchOld=mod(tag-128.0,2.0);cpSurfaceClass=floor((tag-128.0)/2.0);}
}
