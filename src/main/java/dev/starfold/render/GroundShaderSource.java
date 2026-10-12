package dev.starfold.render;

import java.util.regex.Pattern;

/** Like the CoreShaderSource path: keep the original model/shading, warp its final vertices only. */
public final class GroundShaderSource {
    private static final Pattern MAIN =
            Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    public static boolean supported(String name) {
        return CoreShaderSource.supported(name);
    }

    public static String patch(String name, boolean vertex, String source) {
        return wrap(name, vertex, hooks(name, vertex, source));
    }

    /** The only edits inside upstream code, made before any other mod's shader transformer runs: record the
     *  view-space position and route the block texture sample. The entry point keeps its name and its locals,
     *  so transformers that read or extend main (Veil's dynamic buffers, Sable's lighting) see it as upstream wrote it. */
    public static String hooks(String name, boolean vertex, String source) {
        if (!supported(name)
                || source.contains(vertex ? "vec4 cpNativeModelView;" : "vec4 cpNativeSample("))
            return source;
        var match = MAIN.matcher(source);
        if (!match.find())
            throw new IllegalArgumentException("Native projection shader main: " + name);
        if (vertex) {
            if (!source.contains("uniform mat4 ProjMat"))
                throw new IllegalArgumentException("Native projection matrix: " + name);
            return captureView(source);
        }
        // Veil cannot serialize function prototypes (null body). Define the helper before
        // the upstream sampler call, with its own inputs, rather than forward-declaring it.
        source =
                source.substring(0, match.start())
                        + "uniform float CPNativeActive, CPNativeCover, CPNativeLowFrequency, CPNativeStitch;\nflat in float cpNativeTexSpan;\nin vec3 cpNativeSourcePoint;\n"
                        + TEXTURE
                        + "\n"
                        + source.substring(match.start());
        return source.replaceAll(
                "texture\\s*\\(\\s*Sampler0\\s*,\\s*texCoord0\\s*\\)",
                "cpNativeSample(Sampler0,texCoord0)");
    }

    /** Our own code: rename the finished entry point and append the projection around it. Runs after every other
     *  transformer, so none of this text passes through another mod's GLSL parser or serializer. */
    public static String wrap(String name, boolean vertex, String source) {
        if (!supported(name) || source.contains("void cpNativeOriginalMain(")) return source;
        // Veil's AST serializer loses grouping in vec2 + (condition ? b : a).
        // Identical helper semantics, explicit branch; never patch upstream model/shading code.
        source =
                source.replace(
                        "return CPOrbitAnchor.xy+(dot(b,b)<dot(a,a)?b:a);",
                        "vec2 nearest=a; if(dot(b,b)<dot(a,a)) nearest=b; return CPOrbitAnchor.xy+nearest;");
        var match = MAIN.matcher(source);
        if (!match.find())
            throw new IllegalArgumentException("Native projection shader main: " + name);
        String original = match.replaceFirst("void cpNativeOriginalMain() {");
        String lightmap =
                source.contains("minecraft_sample_lightmap(Sampler2")
                        ? "minecraft_sample_lightmap(Sampler2,UV2).rgb"
                        : "texelFetch(Sampler2,UV2/16,0).rgb";
        boolean selfLit =
                name.contains("glint")
                        || name.contains("eyes")
                        || name.contains("emissive")
                        || name.contains("beacon_beam")
                        || name.contains("energy_swirl");
        if (vertex) {
            if (!source.contains("uniform mat4 ProjMat"))
                throw new IllegalArgumentException("Native projection matrix: " + name);
            return original
                    + "\n"
                    + VERTEX.replace(
                                    "// CP_NATIVE_VOLUME",
                                    source.contains("in vec3 Normal;")
                                            ? "if(CPNativeTerrain>0.5&&CPNativeDerived<0.5&&CPNativeVolume>0.5)cpNativeSourcePoint.xz-=Normal.xz*0.02;"
                                            : "")
                            .replace(
                                    "// CP_NATIVE_STITCH",
                                    source.contains("in vec3 Normal;")
                                            ? "if(CPNativeStitch>0.5){cpNativeSourcePoint.xz-=Normal.xz*0.02;if(Normal.x< -0.5)cpNativeSide=1.0;else if(Normal.x>0.5)cpNativeSide=2.0;else if(Normal.z< -0.5)cpNativeSide=3.0;else cpNativeSide=4.0;}"
                                            : "")
                            .replace(
                                    "// CP_NATIVE_SCREEN",
                                    name.endsWith("rendertype_lines")
                                            ? "if(abs(gl_Position.w)>0.000001&&abs(originalClip.w)>0.000001)screenOffset=gl_Position.xy/gl_Position.w-originalClip.xy/originalClip.w;"
                                            : "")
                            .replace(
                                    "// CP_NATIVE_TEX",
                                    source.contains("in vec4 Color;")
                                                    && source.contains("out vec4 vertexColor;")
                                                    && source.contains("in vec3 Normal;")
                                            ? "if(CPNativeCover>0.5){float tag=round(Color.a*255.0);cpNativeTexSpan=max(1.0,tag);if(tag<0.5)cpNativeSide=1.0;else if(tag>251.5)cpNativeSide=tag-250.0;vertexColor.rgb=mix(Color.rgb,Normal*0.5+0.5,CPNativeLowFrequency);vertexColor.a=1.0;}"
                                            : "")
                            .replace(
                                    "// CP_NATIVE_FOG",
                                    source.contains("out float vertexDistance;")
                                            ? "vertexDistance = length(point.xz-CPNativeEye.xz)*(1.0-smoothstep(128.0,160.0,CPNativeEye.y));"
                                            : "")
                            .replace(
                                    "// CP_NATIVE_LIGHT",
                                    !selfLit
                                                    && source.contains("in ivec2 UV2;")
                                                    && source.contains(
                                                            "uniform sampler2D Sampler2;")
                                                    && source.contains("out vec4 vertexColor;")
                                            ? "if(CPNativeDerived>0.5||CPNativeTerrain>0.5){vertexColor.rgb/=max(vec3(0.0001),"
                                                    + lightmap
                                                    + ");if(CPNativeCoarse<0.5){cpNativeBlockLight=texelFetch(Sampler2,ivec2(UV2.x/16,0),0).rgb;float sky=clamp(float(UV2.y)/240.0,0.0,1.0);cpNativeSkyVisibility=sky/(4.0-3.0*sky);}}"
                                            : "");
        }
        // Upstream linear_fog has already mixed the sky into fragColor. Relight the material only:
        // (material*(1-f)+fog*f)*L + fog*f*(1-L). Otherwise distant dark risers survive inside blue
        // fog as false holes.
        return original
                + "\nuniform float CPNativeTerrain, CPNativeDerived, CPNativeCoarse, CPNativeSelection, CPNativeVolume, CPNativeFadeMix;\nuniform vec4 CPNativeFade, CPNativeClosure;\nuniform vec3 CPNativeMask, CPNativeSun;\nuniform float CPNativeFace, CPNativeSize;\nuniform sampler2D CPNativeCoverage;\nin float cpNativeFacing, cpNativeRangeFog;\nin vec3 cpNativeSolarPosition, cpNativeSolarNormal, cpNativeBlockLight, cpNativeRelative;\nin float cpNativeSea, cpNativeSkyVisibility;\nflat in float cpNativeSide;\n"
                + RELIEF
                + "\n"
                + OWNERSHIP
                + "\n"
                + FADE
                + "\nvoid main(){vec3 coverNormal=cpNativeCoverNormal();cpNativeOriginalMain();if(CPNativeActive>0.5){if(cpNativeFacing<0.0)discard;bool nativeVolume=CPNativeVolume>0.5&&all(greaterThanEqual(cpNativeSourcePoint.xz,vec2(0.0)))&&all(lessThan(cpNativeSourcePoint.xz,vec2(CPNativeSize)))&&(CPNativeSelection<0.5||cpNativeOwner(ivec2(floor(cpNativeSourcePoint.xz/16.0)))>0.75);float far=cpNativeFar(),dither=cpNativeDither();bool deep=dither<far,lod=far>0.0&&dither<far+0.125;if(nativeVolume&&((CPNativeCover>0.5&&!lod)||CPNativeStitch>0.5))discard;if(CPNativeCover>0.5||CPNativeStitch>0.5){ivec2 tile=ivec2(floor(cpNativeSourcePoint.xz/16.0));float owner=cpNativeOwner(tile);if(CPNativeCover>0.5&&owner>0.1&&!lod)discard;if(CPNativeStitch>0.5){if(owner<0.1||deep)discard;ivec2 neighbour=cpNativeNeighbour(tile);if(cpNativeOwner(neighbour)>0.1)discard;}if(CPNativeCover>0.5&&cpNativeSide>0.5){vec2 local=fract(cpNativeSourcePoint.xz/16.0);ivec2 neighbour=tile;bool border=false;if(cpNativeSide<1.5){neighbour.x-=1;border=local.x<0.01;}else if(cpNativeSide<2.5){neighbour.x+=1;border=local.x>0.99;}else if(cpNativeSide<3.5){neighbour.y-=1;border=local.y<0.01;}else{neighbour.y+=1;border=local.y>0.99;}if(border&&cpNativeOwner(neighbour)>0.1)discard;} }if(CPNativeTerrain>0.5){if(CPNativeDerived<0.5&&deep)discard;if(CPNativeDerived<0.5&&(CPNativeMask.z>0.5&&length(cpNativeRelative)>512.0||(CPNativeClosure[(cpNativeSourcePoint.x<CPNativeSize*0.5?0:2)+(cpNativeSourcePoint.z<CPNativeSize*0.5?0:1)]<0.5&&min(abs(cpNativeSourcePoint.x),abs(CPNativeSize-cpNativeSourcePoint.x))<CPNativeMask.x&&min(abs(cpNativeSourcePoint.z),abs(CPNativeSize-cpNativeSourcePoint.z))<CPNativeMask.x&&cpNativeSourcePoint.y>=-8.0&&cpNativeSourcePoint.y<CPNativeMask.y)))discard;}}if(CPNativeActive>0.5&&(CPNativeDerived>0.5||CPNativeTerrain>0.5)&&"
                + (!selfLit)
                + "){float daylight=max(0.0,dot(normalize(CPNativeSun-cpNativeSolarPosition),coverNormal));vec3 cpLight=max(vec3((0.12+0.88*daylight)*cpNativeSkyVisibility),cpNativeBlockLight);fragColor.rgb*=cpLight;// CP_NATIVE_LIT_FOG\n}// CP_NATIVE_RANGE_FOG\n}\n"
                        .replace(
                                "// CP_NATIVE_LIT_FOG",
                                !selfLit && linearFogInterface(source)
                                        ? "float cpFog=smoothstep(FogStart,FogEnd,vertexDistance)*FogColor.a;fragColor.rgb+=FogColor.rgb*cpFog*(vec3(1.0)-cpLight);"
                                        : "")
                        .replace(
                                "// CP_NATIVE_RANGE_FOG",
                                source.contains("uniform vec4 FogColor;")
                                        ? "if(CPNativeActive>0.5)fragColor.rgb=mix(fragColor.rgb,FogColor.rgb,cpNativeRangeFog);"
                                        : "");
    }

    /** Includes may define linear_fog and its parameter names even when this program has no fog inputs. */
    static boolean linearFogInterface(String source) {
        if (!source.contains("linear_fog(")) return false;
        for (String declaration :
                new String[] {
                    "uniform\\s+vec4\\s+FogColor\\s*;",
                    "in\\s+float\\s+vertexDistance\\s*;",
                    "uniform\\s+float\\s+[^;]*\\bFogStart\\b[^;]*;",
                    "uniform\\s+float\\s+[^;]*\\bFogEnd\\b[^;]*;"
                }) if (!Pattern.compile("\\b" + declaration).matcher(source).find()) return false;
        return true;
    }

    /** Keep upstream animation/ChunkOffset in view space, before lossy perspective depth encoding. */
    static String captureView(String source) {
        var projection =
                Pattern.compile("(gl_Position|vec4\\s+\\w+)\\s*=\\s*ProjMat\\s*\\*\\s*([^;]+);")
                        .matcher(source);
        if (!projection.find())
            throw new IllegalArgumentException("Native upstream projection capture missing");
        String replacement =
                "cpNativeModelView=" + projection.group(2) + ";\n" + projection.group();
        source = projection.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement));
        var entry =
                Pattern.compile("\\bvoid\\s+(?:main|cpCapOriginalMain|cpNativeOriginalMain)\\s*\\(")
                        .matcher(source);
        entry.find();
        return source.substring(0, entry.start())
                + "vec4 cpNativeModelView;\n"
                + source.substring(entry.start());
    }

    static final String OWNERSHIP =
            """
        ivec2 cpNativeNeighbour(ivec2 tile){
            if(cpNativeSide<1.5)tile.x-=1;else if(cpNativeSide<2.5)tile.x+=1;else if(cpNativeSide<3.5)tile.y-=1;else tile.y+=1;
            return tile;
        }
        float cpNativeOwner(ivec2 tile){
            tile=clamp(tile,ivec2(-1),ivec2(128));
            return texelFetch(CPNativeCoverage,tile+ivec2(1,1+int(CPNativeFace)*130),0).r;
        }
        """;

    /** Real blocks hand over to the far terrain through complementary screen-door coverage: both layers use one
     *  threshold per pixel, the far terrain a little more of it, so the overlap is resolved by depth and never opens to sky. */
    static final String FADE =
            """
        float cpNativeFar(){
            float range=smoothstep(CPNativeFade.x,CPNativeFade.y,length(cpNativeRelative.xz))*CPNativeFadeMix;
            return max(range,smoothstep(CPNativeFade.z,CPNativeFade.w,-cpNativeRelative.y));
        }
        float cpNativeDither(){return fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(0.06711056,0.00583715))));}
        """;

    static final String RELIEF =
            """
        vec3 cpNativeCoverNormal(){
            // Evaluate derivatives before any alpha/facing/coverage discard, in uniform flow.
            vec3 slope=cross(dFdx(cpNativeSolarPosition),dFdy(cpNativeSolarPosition));
            vec3 radial=normalize(cpNativeSolarNormal);
            if((CPNativeCover<0.5&&CPNativeTerrain<0.5)||((CPNativeCoarse>0.5||CPNativeCover>0.5)&&cpNativeSea>0.999)||dot(slope,slope)<0.000000000001)return radial;
            // A heightfield is outward-facing; a voxel ceiling genuinely faces inward.
            if(CPNativeCoarse>0.5||CPNativeCover>0.5){if(dot(slope,radial)<0.0)slope=-slope;}
            else if(!gl_FrontFacing)slope=-slope;
            return normalize(slope);
        }
        """;
    public static final String TEXTURE =
            """
        vec4 cpNativeSample(sampler2D source,vec2 uv){
            if(CPNativeActive>0.5&&CPNativeStitch>0.5)return vec4(vec3(1.0),texture(source,uv).a);
            if(CPNativeActive<0.5||CPNativeCover<0.5)return texture(source,uv);
            if(CPNativeLowFrequency>=1.0)return vec4(1.0);
            return mix(texture(source,uv),vec4(1.0),CPNativeLowFrequency);
        }
        """;
    static final String VERTEX =
            """
        uniform float CPNativeActive, CPNativeDerived, CPNativeCoarse, CPNativeCover, CPNativeLowFrequency, CPNativeStitch, CPNativeTerrain, CPNativeVolume;
        uniform vec3 CPNativeEye, CPNativeViewOffset, CPNativeWorldX, CPNativeWorldY, CPNativeWorldZ;
        uniform vec3 CPNativeAirNormal, CPNativeAirU, CPNativeAirV;
        uniform vec4 CPNativeAirLimits, CPNativeOrbitLimits;
        uniform vec3 CPNativeOrbitNormal, CPNativeOrbitU, CPNativeOrbitV, CPNativeOrbitOffset, CPNativeLens;
        uniform vec3 CPNativeSourcePlot, CPNativeSourceNormal, CPNativeSourceU, CPNativeSourceV;
        uniform vec3 CPNativeFlatOrigin, CPNativeFlatU, CPNativeFlatV;
        uniform vec2 CPNativeRange;
        // Blocks along a face edge of the observed planet.
        uniform float CPNativeSize;
        out float cpNativeFacing, cpNativeRangeFog, cpNativeSea, cpNativeSkyVisibility;
        flat out float cpNativeTexSpan, cpNativeSide;
        out vec3 cpNativeSourcePoint, cpNativeSolarPosition, cpNativeSolarNormal, cpNativeBlockLight, cpNativeRelative;
        vec3 cpNativeDirection(vec2 p){vec3 q=CPNativeSourceNormal*(CPNativeSize*0.5)+CPNativeSourceU*(p.x-(CPNativeSize*0.5))+CPNativeSourceV*(p.y-(CPNativeSize*0.5));if(p.x<0.0)q=-CPNativeSourceU*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*0.5)+p.x)+CPNativeSourceV*(p.y-(CPNativeSize*0.5));else if(p.x>CPNativeSize)q=CPNativeSourceU*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*1.5)-p.x)+CPNativeSourceV*(p.y-(CPNativeSize*0.5));else if(p.y<0.0)q=-CPNativeSourceV*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*0.5)+p.y)+CPNativeSourceU*(p.x-(CPNativeSize*0.5));else if(p.y>CPNativeSize)q=CPNativeSourceV*(CPNativeSize*0.5)+CPNativeSourceNormal*((CPNativeSize*1.5)-p.y)+CPNativeSourceU*(p.x-(CPNativeSize*0.5));return normalize(q);}
        vec3 cpNativeAirDirection(vec2 p){vec3 q=vec3(p.x-(CPNativeSize*0.5),(CPNativeSize*0.5),p.y-(CPNativeSize*0.5));if(p.x<0.0)q=vec3(-(CPNativeSize*0.5),(CPNativeSize*0.5)+p.x,p.y-(CPNativeSize*0.5));else if(p.x>CPNativeSize)q=vec3((CPNativeSize*0.5),(CPNativeSize*1.5)-p.x,p.y-(CPNativeSize*0.5));else if(p.y<0.0)q=vec3(p.x-(CPNativeSize*0.5),(CPNativeSize*0.5)+p.y,-(CPNativeSize*0.5));else if(p.y>CPNativeSize)q=vec3(p.x-(CPNativeSize*0.5),(CPNativeSize*1.5)-p.y,(CPNativeSize*0.5));return normalize(q);}
        uniform vec2 CPNativeGapCorner, CPNativeGapSigns;
        uniform vec3 CPNativeGapLimits;
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
            vec4 ordinary=ProjMat*view;
            if(CPNativeLens.x<=0.0)return ordinary;
            float r=max(0.000001,length(view.xyz));
            vec2 corner=1.0/CPNativeLens.yz;float rho2=dot(corner,corner),cosine=(4.0-rho2)/(4.0+rho2);
            cpNativeFacing=min(cpNativeFacing,-view.z-r*cosine);
            float d=max(r*(1.0+cosine),r-view.z);
            vec4 stereo=vec4(2.0*CPNativeLens.yz*view.xy*r/d,-ProjMat[2][2]*r+ProjMat[3][2],r);
            return mix(ordinary,stereo,CPNativeLens.x);
        }
        void main(){
            cpNativeOriginalMain();cpNativeFacing=1.0;cpNativeRangeFog=0.0;cpNativeSea=0.0;cpNativeSkyVisibility=1.0;cpNativeSide=0.0;cpNativeTexSpan=1.0;cpNativeSourcePoint=vec3(1024.0);cpNativeSolarPosition=vec3(0);cpNativeSolarNormal=vec3(0,1,0);cpNativeBlockLight=vec3(0);cpNativeRelative=vec3(0);
            if(CPNativeActive<0.5)return;
            // CP_NATIVE_LIGHT
            // CP_NATIVE_TEX
            vec4 view=cpNativeModelView;
            vec4 originalClip=ProjMat*view;
            vec2 screenOffset=vec2(0);
            // CP_NATIVE_SCREEN
            if(abs(view.w)<0.000001)return;view/=view.w;
            vec3 relative=vec3(dot(view.xyz,CPNativeWorldX),dot(view.xyz,CPNativeWorldY),dot(view.xyz,CPNativeWorldZ))+CPNativeViewOffset;
            vec3 point=CPNativeEye+relative-CPNativeSourcePlot;cpNativeRelative=relative;
            cpNativeSourcePoint=point;if(abs(point.y)<0.01)cpNativeSea=1.0;cpNativeSolarNormal=cpNativeDirection(point.xz);cpNativeSolarPosition=cpNativeSolarNormal*(CPNativeOrbitLimits.y+point.y);
            // Bias lookup coordinates only; do not add another displacement to the baked wall.
            // CP_NATIVE_STITCH
            // CP_NATIVE_VOLUME
            if(CPNativeCover>0.5&&cpNativeSide>0.5){if(cpNativeSide<1.5)cpNativeSourcePoint.x+=0.02;else if(cpNativeSide<2.5)cpNativeSourcePoint.x-=0.02;else if(cpNativeSide<3.5)cpNativeSourcePoint.z+=0.02;else cpNativeSourcePoint.z-=0.02;}
            if(CPNativeEye.y>160.0){
                cpNativeFacing=dot(cpNativeDirection(point.xz),CPNativeOrbitNormal)-CPNativeOrbitLimits.w;
                // What is by the eye is drawn as it is built: the planet is rounded far off, and whatever carries the
                // observer up here (a ship's deck, a seat, a mount) is not planet. The lens widens the level by
                // (radius+height)/radius; within reach of the eye that would stretch the deck underfoot.
                vec3 rigid=point+CPNativeOrbitOffset;
                point=mix(rigid,cpNativeOrbitRepair(point),smoothstep(96.0,256.0,length(relative)));
                cpNativeFacing=min(cpNativeFacing,CPNativeOrbitLimits.z-length(point.xz-CPNativeEye.xz));
            }else{point=CPNativeFlatOrigin+point.x*CPNativeFlatU+point.z*CPNativeFlatV+vec3(0,point.y,0);if(CPNativeAirLimits.x>0.0)cpNativeFacing=dot(cpNativeAirDirection(point.xz),CPNativeAirNormal);point.xz=cpNativeRepair(point.xz);cpNativeFacing=min(cpNativeFacing,CPNativeGapLimits.z-length(point.xz-CPNativeEye.xz));}
            relative=point-CPNativeEye-CPNativeViewOffset;
            view.xyz=CPNativeWorldX*relative.x+CPNativeWorldY*relative.y+CPNativeWorldZ*relative.z;
            gl_Position=cpNativeProject(view);
            gl_Position.xy+=screenOffset*gl_Position.w;
            cpNativeRangeFog=smoothstep(CPNativeRange.x,CPNativeRange.y,length(point.xz-CPNativeEye.xz))*smoothstep(128.0,160.0,CPNativeEye.y);
            // CP_NATIVE_FOG
        }
        """;

    private GroundShaderSource() {}
}
