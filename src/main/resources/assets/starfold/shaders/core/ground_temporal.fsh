#version 330 core
in vec2 uv;
layout(location=0) out vec4 resolved;
layout(location=1) out float savedDepth;
uniform sampler2D CurrentColor,CurrentDepth,HistoryColor,HistoryDepth;
uniform mat4 Projection,PreviousProjection,CurrentToPreviousView;
uniform vec3 Lens,PreviousLens;
uniform vec2 PixelSize,Jitter,PreviousJitter;
uniform int HasHistory,ZeroToOne;
float ndcDepth(float d){return ZeroToOne!=0?d:d*2.-1.;}
vec3 unproject(vec2 at,float depth,mat4 p,vec3 lens,vec2 jitter){
 vec2 xy=(at*2.-1.-jitter)/vec2(p[0][0],p[1][1]);
 float denominator=p[3][2]/(ndcDepth(depth)+p[2][2]);
 // Direct perspective inversion avoids cancellation in sqrt(1-c*c) near the center.
 if(lens.x<.00001)return vec3(xy,-1)*denominator;
 float rho=length(xy),lo=-.999,hi=1.;float scale=2.*lens.z/p[1][1];
 for(int i=0;i<16;i++){float mid=(lo+hi)*.5;float value=sqrt(max(0.,1.-mid*mid))*((1.-lens.x)+lens.x*scale/(1.+mid))/max(.000001,(1.-lens.x)*mid+lens.x);if(value>rho)lo=mid;else hi=mid;}
 float c=(lo+hi)*.5,radius=denominator/max(.000001,(1.-lens.x)*c+lens.x);
 return vec3(rho>1e-8?xy/rho*sqrt(max(0.,1.-c*c)):vec2(0),-c)*radius;
}
vec4 project(vec3 v,mat4 p,vec3 lens){float r=length(v);vec4 ordinary=p*vec4(v,1);vec4 stereo=vec4(2.*lens.yz*v.xy*r/max(.000001,r-v.z),-p[2][2]*r+p[3][2],r);return mix(ordinary,stereo,lens.x);}
// Catmull-Rom reconstruction avoids repeated bilinear diffusion of the history.
vec3 historyAt(vec2 at){
 vec2 p=at/PixelSize,base=floor(p-.5)+.5,f=p-base;
 vec2 w0=f*(-.5+f*(1.-.5*f));
 vec2 w1=1.+f*f*(-2.5+1.5*f);
 vec2 w2=f*(.5+f*(2.-1.5*f));
 vec2 w3=f*f*(-.5+.5*f);
 vec2 middle=w1+w2;
 vec2 positions[3];positions[0]=(base-1.)*PixelSize;positions[1]=(base+w2/middle)*PixelSize;positions[2]=(base+2.)*PixelSize;
 vec2 weights[3];weights[0]=w0;weights[1]=middle;weights[2]=w3;
 vec3 result=vec3(0);
 for(int y=0;y<3;y++)for(int x=0;x<3;x++)result+=texture(HistoryColor,vec2(positions[x].x,positions[y].y)).rgb*weights[x].x*weights[y].y;
 return result;
}
void main(){
 vec4 current=texture(CurrentColor,uv);float depth=texture(CurrentDepth,uv).r;resolved=current;savedDepth=depth;
 if(HasHistory==0||(ZeroToOne!=0?depth<=1e-7:depth>=.9999999))return;
 vec3 view=unproject(uv,depth,Projection,Lens,Jitter);vec3 previousView=(CurrentToPreviousView*vec4(view,1)).xyz;
 vec4 previous=project(previousView,PreviousProjection,PreviousLens);if(previous.w<=0.)return;
 vec2 previousUv=(previous.xy/previous.w+PreviousJitter)*.5+.5;
 if(any(lessThan(previousUv,PixelSize*.5))||any(greaterThan(previousUv,1.-PixelSize*.5)))return;
 float oldDepth=texture(HistoryDepth,previousUv).r;if(ZeroToOne!=0?oldDepth<=1e-7:oldDepth>=.9999999)return;
 vec3 oldView=unproject(previousUv,oldDepth,PreviousProjection,PreviousLens,PreviousJitter);
 if(length(oldView-previousView)>max(.08,length(previousView)*.006))return;
 vec3 low=current.rgb,high=current.rgb;
 for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){vec3 c=texture(CurrentColor,uv+vec2(x,y)*PixelSize).rgb;low=min(low,c);high=max(high,c);}
 vec3 history=clamp(historyAt(previousUv),low,high);
 float motion=length((previousUv-uv-(PreviousJitter-Jitter)*.5)/PixelSize);
 float weight=exp(-motion*.12)*.6+.3;
 resolved=vec4(mix(current.rgb,history,weight),current.a);
}
