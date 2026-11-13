// Sodium 0.8.x chunk-fragment warping: the horizon/closure discards and the atmosphere grade the
// vanilla core-shader path applies to native terrain, without Sodium's own lighting or fog.
uniform float CPNativeActive, CPNativeSize;
uniform vec3 CPAtmosphereGrade;
uniform float CPAtmosphereFog;
uniform vec4 CPNativeClosure;
uniform vec2 CPNativeMask;

in float cpNativeFacing, cpNativeRangeFog;
in vec3 cpNativeSourcePoint;

void cpNativeFragment(inout vec4 fragColor){
    if(CPNativeActive<0.5)return;
    // Beyond the planet's visible horizon; the far mesh closes the disk there instead.
    if(cpNativeFacing<0.0)discard;
    // Fold-point closure walls sit under the neighbour's top and would read as a false terrace.
    if((CPNativeClosure[(cpNativeSourcePoint.x<CPNativeSize*0.5?0:2)+(cpNativeSourcePoint.z<CPNativeSize*0.5?0:1)]<0.5
            &&min(abs(cpNativeSourcePoint.x),abs(CPNativeSize-cpNativeSourcePoint.x))<CPNativeMask.x
            &&min(abs(cpNativeSourcePoint.z),abs(CPNativeSize-cpNativeSourcePoint.z))<CPNativeMask.x
            &&cpNativeSourcePoint.y>=-8.0&&cpNativeSourcePoint.y<CPNativeMask.y))discard;
    float cpFog=smoothstep(u_FogStart,u_FogEnd,v_FragDistance)*u_FogColor.a;
    fragColor.rgb*=CPAtmosphereGrade;
    fragColor.rgb+=u_FogColor.rgb*cpFog*(vec3(1.0)-CPAtmosphereGrade);
    fragColor.rgb=mix(fragColor.rgb,u_FogColor.rgb,cpNativeRangeFog*CPAtmosphereFog);
}
