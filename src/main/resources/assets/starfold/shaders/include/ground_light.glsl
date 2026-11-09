bool cpGroundHidden(float fade){
    float threshold=fract(dot(floor(gl_FragCoord.xy),vec2(0.754877666,0.569840296))+0.5);
    if(fade<0.0)return threshold<(-fade-1.0);
    return threshold>=fade;
}
// Solar sky ambient follows local noon, not the orientation of a terrace riser.
float cpGroundDaylight(vec3 sun,vec3 up,vec3 normal){
    float skyDay=max(0.0,dot(sun,up));
    float faceDay=max(0.0,dot(sun,normal));
    return 0.6*skyDay+0.4*faceDay;
}
