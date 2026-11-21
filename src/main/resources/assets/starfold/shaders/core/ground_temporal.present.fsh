#version 330 core
in vec2 uv;
out vec4 color;
uniform sampler2D ResolvedColor;
uniform vec2 PixelSize;
void main(){
 vec4 center=texture(ResolvedColor,uv);
 vec3 north=texture(ResolvedColor,uv+vec2(0,PixelSize.y)).rgb;
 vec3 south=texture(ResolvedColor,uv-vec2(0,PixelSize.y)).rgb;
 vec3 east=texture(ResolvedColor,uv+vec2(PixelSize.x,0)).rgb;
 vec3 west=texture(ResolvedColor,uv-vec2(PixelSize.x,0)).rgb;
 vec3 low=min(center.rgb,min(min(north,south),min(east,west)));
 vec3 high=max(center.rgb,max(max(north,south),max(east,west)));
 // Output only: never feed sharpened edges back into temporal history.
 vec3 detail=center.rgb-(north+south+east+west)*.25;
 vec3 correction=clamp(detail*.35,-(high-low)*.12,(high-low)*.12);
 color=vec4(clamp(center.rgb+correction,low,high),center.a);
}
