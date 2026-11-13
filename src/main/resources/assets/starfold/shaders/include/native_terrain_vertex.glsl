// Sodium 0.8.x chunk-vertex warping. Same chart mapping as the vanilla core-shader path, applied
// to Sodium's own block_layer_opaque program instead of the rendertype_* programs it replaces.
uniform float CPNativeActive;
uniform vec3 CPNativeEye, CPNativeViewOffset, CPNativeWorldX, CPNativeWorldY, CPNativeWorldZ;
uniform vec3 CPNativeAirNormal, CPNativeAirU, CPNativeAirV;
uniform vec4 CPNativeAirLimits, CPNativeOrbitLimits;
uniform vec3 CPNativeOrbitNormal, CPNativeOrbitU, CPNativeOrbitV, CPNativeOrbitOffset, CPNativeLens;
uniform vec3 CPNativeSourcePlot, CPNativeSourceNormal, CPNativeSourceU, CPNativeSourceV;
uniform vec3 CPNativeFlatOrigin, CPNativeFlatU, CPNativeFlatV;
uniform vec2 CPNativeRange;
// Blocks along a face edge of the observed planet.
uniform float CPNativeSize;

out float cpNativeFacing, cpNativeRangeFog;
out vec3 cpNativeSourcePoint;

uniform vec2 CPNativeGapCorner, CPNativeGapSigns;
uniform vec3 CPNativeGapLimits;

vec3 cpNativeDirection(vec2 p){vec3 q=CPNativeSourceNormal*(CPNativeSize*0.5)+CPNativeSourceU*(p.x-(CPNativeSize*0.5))+CPNativeSourceV*(p.y-(CPNativeSize*0.5));if(p.x<0.0)q=-CPNativeSourceU*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*0.5)+p.x)+CPNativeSourceV*(p.y-(CPNativeSize*0.5));else if(p.x>CPNativeSize)q=CPNativeSourceU*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*1.5)-p.x)+CPNativeSourceV*(p.y-(CPNativeSize*0.5));else if(p.y<0.0)q=-CPNativeSourceV*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*0.5)+p.y)+CPNativeSourceU*(p.x-(CPNativeSize*0.5));else if(p.y>CPNativeSize)q=CPNativeSourceV*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*1.5)-p.y)+CPNativeSourceU*(p.x-(CPNativeSize*0.5));return normalize(q);}
vec3 cpNativeAirDirection(vec2 p){vec3 q=vec3(p.x-(CPNativeSize*0.5),(CPNativeSize*0.5),p.y-(CPNativeSize*0.5));if(p.x<0.0)q=vec3(-(CPNativeSize*0.5),(CPNativeSize*0.5)+p.x,p.y-(CPNativeSize*0.5));else if(p.x>CPNativeSize)q=vec3((CPNativeSize*0.5),(CPNativeSize*1.5)-p.x,p.y-(CPNativeSize*0.5));else if(p.y<0.0)q=vec3(p.x-(CPNativeSize*0.5),(CPNativeSize*0.5)+p.y,-(CPNativeSize*0.5));else if(p.y>CPNativeSize)q=vec3(p.x-(CPNativeSize*0.5),(CPNativeSize*1.5)-p.y,(CPNativeSize*0.5));return normalize(q);}
vec2 cpNativeGap(vec2 p){
    if(CPNativeGapLimits.x==0.0)return p;
    vec2 q=(p-CPNativeGapCorner)*CPNativeGapSigns;float r=length(q);
    if(r>=2.0*CPNativeGapLimits.y)return p;
    if(!((q.x<0.0&&q.y>=0.0)||(q.y<0.0&&q.x>=0.0)))return p;
    vec2 e=(CPNativeEye.xz-CPNativeGapCorner)*CPNativeGapSigns;
    float theta=q.x<0.0?3.141592653589793-atan(max(q.y,0.0),-q.x):-1.5707963267948966+atan(max(q.x,0.0),-q.y);
    float eyeAngle=atan(e.y,e.x),delta=theta-eyeAngle;if(abs(delta)<=1.832595714594046)return p;
    if(delta>2.356194490192345)delta-=4.71238898038469;else if(delta< -2.356194490192345)delta+=4.71238898038469;
    float target=eyeAngle+delta+sign(delta)*0.7853981633974483*smoothstep(1.832595714594046,2.356194490192345,abs(delta));float shift=target-theta;
    if(shift>3.141592653589793)shift-=6.283185307179586;else if(shift< -3.141592653589793)shift+=6.283185307179586;
    float amplitude=CPNativeGapLimits.x*smoothstep(56.0,88.0,sqrt(dot(e,e)+r*r))*(1.0-smoothstep(CPNativeGapLimits.y,2.0*CPNativeGapLimits.y,r));float angle=shift*amplitude;
    float c=cos(angle),s=sin(angle);return CPNativeGapCorner+CPNativeGapSigns*vec2(q.x*c-q.y*s,q.x*s+q.y*c);
}
vec2 cpNativeNode(vec2 p){
    vec3 s=cpNativeAirDirection(p);
    vec3 d=(s-CPNativeAirNormal)*(2.0*CPNativeAirLimits.y/max(0.000001,1.0+dot(s,CPNativeAirNormal)));
    vec2 warped=CPNativeEye.xz+vec2(dot(d,CPNativeAirU),dot(d,CPNativeAirV))+CPNativeAirLimits.zw;
    return mix(cpNativeGap(p),warped,CPNativeAirLimits.x);
}
vec2 cpNativeRepair(vec2 p){
    vec2 low=floor(p/16.0)*16.0,f=(p-low)/16.0;
    return mix(mix(cpNativeNode(low),cpNativeNode(low+vec2(16,0)),f.x),mix(cpNativeNode(low+vec2(0,16)),cpNativeNode(low+vec2(16,16)),f.x),f.y);
}
vec3 cpNativeOrbitNode(vec2 p,float h){
    vec3 s=cpNativeDirection(p);
    float c=dot(s,CPNativeOrbitNormal),k=CPNativeOrbitLimits.x;
    float d=max(0.000000001,1.0+c+k*k*(1.0-c));
    vec3 t=s-CPNativeOrbitNormal*c;
    float scale=2.0*(CPNativeOrbitLimits.y+k*h)/d;
    return vec3(CPNativeEye.x+dot(t,CPNativeOrbitU)*scale,h*(1.0-2.0*k*k*(1.0-c)/d)-2.0*CPNativeOrbitLimits.y*k*(1.0-c)/d,CPNativeEye.z+dot(t,CPNativeOrbitV)*scale);
}
vec3 cpNativeOrbitRepair(vec3 p){
    vec2 low=floor(p.xz/16.0)*16.0,f=(p.xz-low)/16.0;
    return mix(mix(cpNativeOrbitNode(low,p.y),cpNativeOrbitNode(low+vec2(16,0),p.y),f.x),mix(cpNativeOrbitNode(low+vec2(0,16),p.y),cpNativeOrbitNode(low+vec2(16,16),p.y),f.x),f.y)+CPNativeOrbitOffset;
}
vec4 cpNativeProject(vec4 view){
    vec4 ordinary=u_ProjectionMatrix*view;
    if(CPNativeLens.x<=0.0)return ordinary;
    float r=max(0.000001,length(view.xyz));
    vec2 corner=1.0/CPNativeLens.yz;float rho2=dot(corner,corner),cosine=(4.0-rho2)/(4.0+rho2);
    cpNativeFacing=min(cpNativeFacing,-view.z-r*cosine);
    float d=max(r*(1.0+cosine),r-view.z);
    vec4 stereo=vec4(2.0*CPNativeLens.yz*view.xy*r/d,-u_ProjectionMatrix[2][2]*r+u_ProjectionMatrix[3][2],r);
    return mix(ordinary,stereo,CPNativeLens.x);
}

// Sodium hands us the camera-relative flat position it is about to project.
void cpNativeWarp(vec3 cpChunkPosition){
    cpNativeFacing=1.0;
    cpNativeRangeFog=0.0;
    cpNativeSourcePoint=vec3(1024.0);
    if(CPNativeActive<0.5)return;
    vec4 view=u_ModelViewMatrix*vec4(cpChunkPosition,1.0);
    if(abs(view.w)<0.000001)return;
    view/=view.w;
    vec3 relative=vec3(dot(view.xyz,CPNativeWorldX),dot(view.xyz,CPNativeWorldY),dot(view.xyz,CPNativeWorldZ))+CPNativeViewOffset;
    vec3 point=CPNativeEye+relative-CPNativeSourcePlot;
    cpNativeSourcePoint=point;
    if(CPNativeEye.y>160.0){
        cpNativeFacing=dot(cpNativeDirection(point.xz),CPNativeOrbitNormal)-CPNativeOrbitLimits.w;
        vec3 rigid=point+CPNativeOrbitOffset;
        point=mix(rigid,cpNativeOrbitRepair(point),smoothstep(96.0,256.0,length(relative)));
        cpNativeFacing=min(cpNativeFacing,CPNativeOrbitLimits.z-length(point.xz-CPNativeEye.xz));
    }else{
        point=CPNativeFlatOrigin+point.x*CPNativeFlatU+point.z*CPNativeFlatV+vec3(0,point.y,0);
        if(CPNativeAirLimits.x>0.0)cpNativeFacing=dot(cpNativeAirDirection(point.xz),CPNativeAirNormal);
        point.xz=cpNativeRepair(point.xz);
        cpNativeFacing=min(cpNativeFacing,CPNativeGapLimits.z-length(point.xz-CPNativeEye.xz));
    }
    relative=point-CPNativeEye-CPNativeViewOffset;
    view.xyz=CPNativeWorldX*relative.x+CPNativeWorldY*relative.y+CPNativeWorldZ*relative.z;
    gl_Position=cpNativeProject(view);
    cpNativeRangeFog=smoothstep(CPNativeRange.x,CPNativeRange.y,length(point.xz-CPNativeEye.xz))*smoothstep(128.0,160.0,CPNativeEye.y);
}
