package dev.starfold.render;

import java.util.regex.Pattern;

/**
 * Like the CoreShaderSource path: keep the original model/shading, warp its final vertices only.
 */
public final class GroundShaderSource {
    /** 1..16 are surface spans; 0/252..254 are wall sides. 251 is a volume face with span 16. */
    static final int VOXEL_TAG = 251;

    static final String SOLAR_LIGHT = solarLight();

    private static String solarLight() {
        try (var in =
                GroundShaderSource.class.getResourceAsStream(
                        "/assets/starfold/shaders/include/ground_light.glsl")) {
            if (in == null) throw new IllegalStateException("Missing ground lighting GLSL");
            String source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return source.substring(source.indexOf("float cpGroundDaylight("));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final Pattern MAIN =
            Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    /** The four statically specialized, packed face-arena programs. */
    public static boolean compact(String name) {
        return name.equals("starfold:ground_compact_solid")
                || name.equals("starfold:ground_compact_cutout")
                || name.equals("starfold:ground_compact_translucent")
                || name.equals("starfold:ground_compact_masked");
    }

    public static boolean supported(String name) {
        return compact(name) || CoreShaderSource.supported(name);
    }

    private static String appearanceName(String name) {
        // Existing appearance providers recognize the original compact terrain program.
        return compact(name) ? "starfold:ground_compact" : name;
    }

    public static String patch(String name, boolean vertex, String source) {
        return wrap(name, vertex, hooks(name, vertex, source));
    }

    /**
     * The only edits inside upstream code, made before any other mod's shader transformer runs:
     * record the view-space position and route the block texture sample. The entry point keeps its
     * name and its locals, so transformers that read or extend main (Veil's dynamic buffers,
     * Sable's lighting) see it as upstream wrote it.
     */
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
                        + "uniform float CPNativeActive, CPNativeCover;\n"
                        + "flat in float cpNativeTexSpan;\n"
                        + "in vec3 cpNativeSourcePoint;\n"
                        + "flat in float cpNativeRepeat, cpNativeTextureAlpha;\nflat in vec4 cpNativeSprite;\n"
                        + "flat in vec3 cpNativeRepeatTint, cpNativeRepeatAlbedo;\n"
                        + "in vec2 cpNativeBlockUv;\n"
                        + (compact(name) ? texture(name.endsWith("_cutout") || name.endsWith("_masked")) : texture(false))
                        + "\n"
                        + source.substring(match.start());
        return source.replaceAll(
                "texture\\s*\\(\\s*Sampler0\\s*,\\s*texCoord0\\s*\\)",
                "cpNativeSample(Sampler0,texCoord0)");
    }

    /**
     * Our own code: rename the finished entry point and append the projection around it. Runs after
     * every other transformer, so none of this text passes through another mod's GLSL parser or
     * serializer.
     */
    public static String wrap(String name, boolean vertex, String source) {
        if (!supported(name) || source.contains("void cpNativeOriginalMain(")) return source;
        // Veil's AST serializer loses grouping in vec2 + (condition ? b : a).
        // Identical helper semantics, explicit branch; never patch upstream model/shading code.
        source =
                source.replace(
                        "return CPOrbitAnchor.xy+(dot(b,b)<dot(a,a)?b:a);",
                        "vec2 nearest=a; if(dot(b,b)<dot(a,a)) nearest=b; return"
                                + " CPOrbitAnchor.xy+nearest;");
        var match = MAIN.matcher(source);
        if (!match.find())
            throw new IllegalArgumentException("Native projection shader main: " + name);
        String original = match.replaceFirst("void cpNativeOriginalMain() {");
        boolean selfLit =
                name.contains("glint")
                        || name.contains("eyes")
                        || name.contains("emissive")
                        || name.contains("beacon_beam")
                        || name.contains("energy_swirl");
        if (vertex) {
            if (!source.contains("uniform mat4 ProjMat"))
                throw new IllegalArgumentException("Native projection matrix: " + name);
            String patched =
                    original
                            + "\n"
                            + vertexTemplate(name)
                                    .replace(
                                            "// CP_NATIVE_SCREEN",
                                            name.endsWith("rendertype_lines")
                                                    ? "if(abs(gl_Position.w)>0.000001&&abs(originalClip.w)>0.000001)screenOffset=gl_Position.xy/gl_Position.w-originalClip.xy/originalClip.w;"
                                                    : "")
                                    // Match vanilla's filtered lightmap; some supported programs do
                                    // not import light.glsl.
                                    .replace(
                                            "// CP_NATIVE_TEX",
                                            source.contains("in vec4 Color;")
                                                            && source.contains(
                                                                    "uniform sampler2D Sampler2;")
                                                            && source.contains(
                                                                    "out vec4 vertexColor;")
                                                            && source.contains("in vec3 Normal;")
                                                    ? materialVertex(name)
                                                    : "")
                                    .replace(
                                            "// CP_NATIVE_FOG",
                                            source.contains("out float vertexDistance;")
                                                    ? "vertexDistance ="
                                                            + " length(point.xz-CPNativeEye.xz)*(1.0-smoothstep(128.0,160.0,CPNativeEye.y));"
                                                    : "")
                                    .replace("// CP_NATIVE_LIGHT",
                                            !selfLit && source.contains("in ivec2 UV2;")
                                                    && source.contains("uniform sampler2D Sampler2;")
                                            ? "cpNativeSkyVisibility=clamp(float(cpNativeLightUV2.y)/240.0,0.0,1.0);"
                                                    + "cpNativeBlockLight=texture(Sampler2,vec2(clamp(float(cpNativeLightUV2.x)/256.0,0.5/16.0,15.5/16.0),0.5/16.0)).rgb;"
                                            : "");
            if (source.contains("in ivec2 UV2;") && source.contains("in vec4 Color;")) {
                // Every original/helper/dynamic-buffer UV2 reader sees live light coordinates;
                // the compact decoder keeps palette sprite bounds separate.
                patched =
                        patched.replaceAll("\\bUV2\\b", "cpNativeLightUV2")
                                .replace(
                                        "in ivec2 cpNativeLightUV2;",
                                        "in ivec2 UV2;\nivec2 cpNativeLightUV2;")
                                .replace(
                                        "cpNativeOriginalMain();",
                                        "cpNativePackedBounds=UV2;cpNativeLightUV2=UV2;cpNativeOriginalMain();");
            }
            if (name.endsWith("rendertype_solid")
                    || name.endsWith("rendertype_cutout")
                    || name.endsWith("rendertype_cutout_mipped")
                    || name.equals("starfold:ground_compact")
                    || compact(name)) {
                // Shadow inputs before every upstream/helper reader, including cutout's vertex
                // alpha.
                patched =
                        patched.replaceAll("\\bColor\\b", "cpSurfaceColor")
                                .replace(
                                        "in vec4 cpSurfaceColor;",
                                        "in vec4 Color;\nvec4 cpSurfaceColor;")
                                .replaceAll("\\bUV0\\b", "cpSurfaceUV")
                                .replace("in vec2 cpSurfaceUV;", "in vec2 UV0;\nvec2 cpSurfaceUV;")
                                .replace(
                                        "cpNativeOriginalMain();",
                                        """
                                cpSurfaceColor=Color;cpSurfaceUV=UV0;cpSurfaceClass=0.0;
                                if(CPNativeActive>0.5){
                                    // CP_SURFACE_CLASS
                                }
                                cpNativeOriginalMain();
                                """.replace("// CP_SURFACE_CLASS", compact(name) || name.equals(GroundCompact.NAME)
                                        ? "cpSurfaceClass=floor(cpSurfaceUV.y/2.0);cpSurfaceUV.y-=cpSurfaceClass*2.0;"
                                        : "if(CPNativeTerrain>0.5||CPNativeDerived>0.5){float a=round(Color.a*255.0);if(a>=250.0&&a<255.0){cpSurfaceClass=a-250.0;cpSurfaceColor.a=1.0;}}"));
            }
            var surface = dev.starfold.api.PlanetSurfaceRender.vertex(appearanceName(name));
            return patched.replace("// CP_SURFACE_VERTEX_DECLARATIONS", surface.declarations())
                    .replace("// CP_SURFACE_VERTEX", surface.apply());
        }
        if (name.endsWith("rendertype_solid")
                || name.endsWith("rendertype_cutout_mipped")
                || name.endsWith("rendertype_cutout")) {
            // A partially covered MSAA pixel can have its center outside the block face.
            // Sampling there can extrapolate UVs into a different sprite in the block atlas.
            original = original.replaceFirst("(#version[^\\r\\n]*)", "$1\n" + """
                    #if defined(GL_ARB_gpu_shader5)
                    #extension GL_ARB_gpu_shader5 : enable
                    #define CP_BLOCK_UV (CPNativeActive>0.5 ? interpolateAtCentroid(texCoord0) : texCoord0)
                    #else
                    #define CP_BLOCK_UV texCoord0
                    #endif
                    """).replaceAll(
                            "cpNativeSample\\s*\\(\\s*Sampler0\\s*,\\s*texCoord0\\s*\\)",
                            "cpNativeSample(Sampler0,CP_BLOCK_UV)");
        }
        return fragment(name, original, selfLit);
    }

    /** Packed materials are decoded independently of runtime coverage/ownership state. */
    private static String materialVertex(String name) {
        if (!compact(name) && !name.equals(GroundCompact.NAME)) return "";
        return """
                float tag=round(Color.a*255.0);
                if(tag!=255.0){
                cpNativeRepeat=1.0;
                cpNativeSprite=vec4(UV0,vec2(cpNativePackedBounds & ivec2(65535))/65535.0);
                cpNativeRepeatTint=Color.rgb;
                cpNativeRepeatAlbedo=Normal*0.5+0.5;
                cpNativeVoxel=1.0;
                if(tag>=240.0&&tag<=243.0)cpNativeSide=tag-239.0;
                if(tag==234.0){cpNativeCross=1.0;cpNativeTextureAlpha=-2.0;}
                cpNativeTexSpan=16.0;
                vertexColor=vec4(texture(Sampler2,clamp(cpNativeLightUV2/256.0,vec2(0.5/16.0),vec2(15.5/16.0))).rgb,1.0);
                }
                """;
    }

    /** Shared material lighting and fog, after the native or compact visibility decisions. */
    private static String fragmentShading(String source, boolean selfLit) {
        return "if(CPNativeActive>0.5&&(CPNativeDerived>0.5||CPNativeTerrain>0.5)&&"
                + (!selfLit)
                + "){float cpDay=cpGroundDaylight(normalize(CPNativeSunDirection),normalize(cpNativeSolarNormal),coverNormal);vec3 cpLight=clamp(CPNativeAmbientLight+CPNativeSunColor*cpDay,vec3(0.0),vec3(1.0));cpLight=mix(vec3(1.0),cpLight,cpNativeSkyVisibility);cpLight=max(cpLight,cpNativeBlockLight);cpLight=mix(cpLight,cpLight/max(max(cpLight.r,cpLight.g),max(cpLight.b,0.0001)),clamp(CPNativeNightVision,0.0,1.0));fragColor.rgb*=cpLight;// CP_NATIVE_LIT_FOG\n}// CP_ATMOSPHERE_GRADE\n// CP_NATIVE_RANGE_FOG\n}\n"
                        .replace(
                                "// CP_ATMOSPHERE_GRADE",
                                selfLit
                                        ? ""
                                        : "if(CPNativeActive>0.5){fragColor.rgb*=CPAtmosphereGrade;"
                                                + (linearFogInterface(source)
                                                        ? "float"
                                                                + " cpGradeFog=smoothstep(FogStart,FogEnd,vertexDistance)*FogColor.a;fragColor.rgb+=FogColor.rgb*cpGradeFog*(vec3(1.0)-CPAtmosphereGrade);"
                                                        : "")
                                                + "}")
                        .replace(
                                "// CP_NATIVE_LIT_FOG",
                                !selfLit && linearFogInterface(source)
                                        ? "float"
                                                + " cpFog=smoothstep(FogStart,FogEnd,vertexDistance)*FogColor.a;fragColor.rgb+=FogColor.rgb*cpFog*(vec3(1.0)-cpLight);"
                                        : "")
                        .replace(
                                "// CP_NATIVE_RANGE_FOG",
                                source.contains("uniform vec4 FogColor;")
                                        ? "if(CPNativeActive>0.5)fragColor.rgb=mix(fragColor.rgb,FogColor.rgb,cpNativeRangeFog*CPAtmosphereFog);"
                                        : "");
    }

    private static String fragment(String name, String original, boolean selfLit) {
        var surface = dev.starfold.api.PlanetSurfaceRender.near(appearanceName(name));
        boolean packed = compact(name) || name.equals(GroundCompact.NAME);
        boolean masked = name.equals("starfold:ground_compact_masked");
        if (masked) original = original.replaceFirst("(#version[^\\r\\n]*)", "$1\n"
                + "#if defined(GL_ARB_gpu_shader5) && defined(GL_ARB_sample_shading)\n"
                + "#extension GL_ARB_gpu_shader5 : enable\n"
                + "#extension GL_ARB_sample_shading : enable\n#define CP_SAMPLE_OWNERSHIP\n#endif");
        return original + "\n" + """
                uniform float CPNativeTerrain, CPNativeDerived, CPNativeCoarse;
                uniform vec3 CPAtmosphereGrade;
                uniform float CPAtmosphereFog;
                uniform vec3 CPNativeSunDirection=vec3(0,1,0);
                // Preserve vanilla lightmap brightness until the frame supplies solar lighting.
                uniform vec3 CPNativeSunColor=vec3(0.0);
                uniform vec3 CPNativeAmbientLight=vec3(1.0);
                uniform float CPNativeNightVision=0.0;
                in float cpNativeFacing, cpNativeRangeFog, cpNativeSea, cpNativeSkyVisibility;
                in vec3 cpNativeSolarPosition, cpNativeSolarNormal, cpNativeBlockLight, cpNativeRelative;
                flat in float cpSurfaceClass, cpNativeSide, cpNativeVoxel;
                """
                + (masked ? """
                        uniform usampler2D CPNativeMaskTex;
                        uniform float CPNativeFace;
                        bool cpNativeCovered(vec3 point){
                            ivec2 tile=ivec2(floor(point.xz/16.0));
                            if(any(lessThan(tile,ivec2(0)))||any(greaterThanEqual(tile,ivec2(128))))return false;
                            ivec2 at=tile+ivec2(0,int(CPNativeFace)*128);
                            return texelFetch(CPNativeMaskTex,at,0).r!=0u;
                        }
                        """ : "")
                + (!packed ? "uniform vec4 CPNativeClosure;\nuniform vec2 CPNativeMask;\nuniform float CPNativeSize;\n" : "")
                + surface.declarations()
                + SOLAR_LIGHT
                + RELIEF
                + "\nvoid main(){vec3 coverNormal=cpNativeCoverNormal();\n"
                + (masked ? """
                        #ifdef CP_SAMPLE_OWNERSHIP
                        gl_SampleMask[0]=-1;
                        #endif
                        if(CPNativeActive>0.5){
                            #ifdef CP_SAMPLE_OWNERSHIP
                            vec2 tilePoint=mod(cpNativeSourcePoint.xz,16.0);
                            vec2 edge=min(tilePoint,16.0-tilePoint);
                            // Only tile-border pixels need per-sample ownership, not per-sample shading.
                            if(gl_NumSamples>1&&any(lessThanEqual(edge,fwidth(cpNativeSourcePoint.xz)))){
                                bool allowed=false;
                                for(int index=0;index<min(gl_NumSamples,32);index++){
                                    if(cpNativeCovered(interpolateAtSample(cpNativeSourcePoint,index)))
                                        gl_SampleMask[0]&=~(1<<index);
                                    else allowed=true;
                                }
                                if(!allowed)discard;
                            }else
                            #endif
                            if(cpNativeCovered(cpNativeSourcePoint))discard;
                        }
                        """ : "")
                + (!packed ? """
                        if(CPNativeActive>0.5){
                            if(cpNativeFacing<0.0)discard;
                            if(CPNativeTerrain>0.5&&CPNativeDerived<0.5&&
                                CPNativeClosure[(cpNativeSourcePoint.x<CPNativeSize*0.5?0:2)+(cpNativeSourcePoint.z<CPNativeSize*0.5?0:1)]<0.5&&
                                min(abs(cpNativeSourcePoint.x),abs(CPNativeSize-cpNativeSourcePoint.x))<CPNativeMask.x&&
                                min(abs(cpNativeSourcePoint.z),abs(CPNativeSize-cpNativeSourcePoint.z))<CPNativeMask.x&&
                                cpNativeSourcePoint.y>=-8.0&&cpNativeSourcePoint.y<CPNativeMask.y)discard;
                        }
                        """ : "")
                + "cpNativeOriginalMain();" + surface.apply()
                + fragmentShading(original, selfLit);
    }

    /**
     * Includes may define linear_fog and its parameter names even when this program has no fog
     * inputs.
     */
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

    /**
     * Keep upstream animation/ChunkOffset in view space, before lossy perspective depth encoding.
     */
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

    static final String RELIEF =
            """
            vec3 cpNativeCoverNormal(){
                // Evaluate derivatives in uniform flow, before any fragment rejection.
                vec3 slope=cross(dFdx(cpNativeSolarPosition),dFdy(cpNativeSolarPosition));
                vec3 radial=normalize(cpNativeSolarNormal);
                if(cpNativeVoxel>0.5){if(!gl_FrontFacing)slope=-slope;return dot(slope,slope)<0.000000000001?radial:normalize(slope);}
                if((CPNativeDerived<0.5&&CPNativeTerrain<0.5)||((CPNativeCoarse>0.5||CPNativeDerived>0.5)&&cpNativeSea>0.999)||dot(slope,slope)<0.000000000001)return radial;
                // A heightfield is outward-facing; a voxel ceiling genuinely faces inward.
                if(CPNativeCoarse>0.5||CPNativeDerived>0.5){if(dot(slope,radial)<0.0)slope=-slope;}
                else if(!gl_FrontFacing)slope=-slope;
                return normalize(slope);
            }
            """;
    private static final String ALPHA_TEST =
            "if(cpNativeTextureAlpha<0.0 && texel.a<0.1)discard;";

    public static final String TEXTURE = texture(true);

    private static String texture(boolean alpha) {
        return """
            vec4 cpNativeSample(sampler2D source,vec2 uv){
                if(CPNativeActive<0.5)return texture(source,uv);
                if(cpNativeRepeat>0.5){
                    if(cpNativeSprite.x<0.0)return vec4(cpNativeRepeatTint,1.0);
                    vec2 span=cpNativeSprite.zw-cpNativeSprite.xy;
                    // Gradients must be taken BEFORE fract; block borders must not select a coarse mip.
                    vec2 dx=dFdx(cpNativeBlockUv)*span,dy=dFdy(cpNativeBlockUv)*span;
                    vec2 atlas=vec2(textureSize(source,0));
                    float footprint=max(1.0,max(length(dx*atlas),length(dy*atlas)));
                    float limit=max(1.0,min(span.x*atlas.x,span.y*atlas.y));
                    float scale=min(1.0,limit/footprint);dx*=scale;dy*=scale;
                    // Keep both trilinear mip footprints inside the sprite, not just the centre UV.
                    vec2 inset=min(span*0.5,vec2(0.5*exp2(ceil(log2(min(footprint,limit)))))/atlas);
                    vec2 repeated=clamp(cpNativeSprite.xy+fract(cpNativeBlockUv)*span,cpNativeSprite.xy+inset,max(cpNativeSprite.xy+inset,cpNativeSprite.zw-inset));
                    // Plants read the base level like vanilla's unmipped cutout layer: atlas mips average their
                    // transparent black texels in, so a plane seen edge-on turns into a dark streak.
                    vec4 texel=cpNativeTextureAlpha<-1.5?texelFetch(source,ivec2(repeated*atlas),0):textureGrad(source,repeated,dx,dy);
                    vec3 rgb=texel.rgb*cpNativeRepeatTint;
                    // CP_NATIVE_ALPHA_TEST
                    // Distance selects the atlas mip, not a second altitude-dependent albedo.
                    return vec4(rgb,cpNativeTextureAlpha<0.0?1.0:cpNativeTextureAlpha);
                }
                return texture(source,uv);
            }
            """.replace("// CP_NATIVE_ALPHA_TEST", alpha ? ALPHA_TEST : "");
    }

    private static String vertexTemplate(String name) {
        boolean compact = compact(name);
        return VERTEX.replace("// CP_NATIVE_CLIP_INIT", compact ? "gl_ClipDistance[0]=1.0;" : "")
                .replace("// CP_NATIVE_CLIP", compact ? "gl_ClipDistance[0]=cpNativeFacing;" : "");
    }

    static final String VERTEX =
            """
            uniform float CPNativeActive, CPNativeDerived, CPNativeCoarse, CPNativeCover, CPNativeTerrain;
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
            flat out float cpSurfaceClass;
            flat out float cpNativeTexSpan, cpNativeSide, cpNativeVoxel;
            flat out float cpNativeRepeat, cpNativeTextureAlpha;
            float cpNativeCross;
            flat out vec4 cpNativeSprite;
            flat out vec3 cpNativeRepeatTint,cpNativeRepeatAlbedo;
            out vec2 cpNativeBlockUv;
            ivec2 cpNativePackedBounds;
            out vec3 cpNativeSourcePoint, cpNativeSolarPosition, cpNativeSolarNormal, cpNativeBlockLight, cpNativeRelative;
            // CP_SURFACE_VERTEX_DECLARATIONS
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
                // CP_NATIVE_CLIP_INIT
                cpNativeOriginalMain();cpNativeCross=0.0;cpNativeTextureAlpha=1.0;cpNativeRepeat=0.0;cpNativeSprite=vec4(0.0);cpNativeRepeatTint=vec3(1.0);cpNativeRepeatAlbedo=vec3(1.0);cpNativeBlockUv=vec2(0.0);cpNativeFacing=1.0;cpNativeRangeFog=0.0;cpNativeSea=0.0;cpNativeSkyVisibility=1.0;cpNativeSide=0.0;cpNativeTexSpan=1.0;cpNativeVoxel=0.0;cpNativeSourcePoint=vec3(1024.0);cpNativeSolarPosition=vec3(0);cpNativeSolarNormal=vec3(0,1,0);cpNativeBlockLight=vec3(0);cpNativeRelative=vec3(0);
                if(CPNativeActive<0.5)return;
                // A flat chart contains the observer and four neighbours, never the opposite face.
                if(CPNativeEye.y<=160.0&&CPNativeSourceNormal.y< -0.5){
                    cpNativeFacing=-1.0;gl_Position=vec4(0.0,0.0,2.0,1.0);
                    // CP_NATIVE_CLIP
                    return;
                }
                // CP_NATIVE_LIGHT
                // CP_NATIVE_TEX
                vec4 view=cpNativeModelView;
                vec4 originalClip=ProjMat*view;
                vec2 screenOffset=vec2(0);
                // CP_NATIVE_SCREEN
                if(abs(view.w)<0.000001)return;view/=view.w;
                vec3 relative=vec3(dot(view.xyz,CPNativeWorldX),dot(view.xyz,CPNativeWorldY),dot(view.xyz,CPNativeWorldZ))+CPNativeViewOffset;
                vec3 point=CPNativeEye+relative-CPNativeSourcePlot;
                if(cpNativeRepeat>0.5){cpNativeBlockUv=point.xz;if(cpNativeSide>0.5&&cpNativeSide<2.5)cpNativeBlockUv=vec2(point.z,-point.y);else if(cpNativeSide>2.5)cpNativeBlockUv=vec2(point.x,-point.y);if(cpNativeCross>0.5)cpNativeBlockUv=vec2((fract(point.x)-0.1464466)*1.41421356,-point.y);}
                cpNativeSourcePoint=point;if(abs(point.y)<0.01)cpNativeSea=1.0;cpNativeSolarNormal=cpNativeDirection(point.xz);cpNativeSolarPosition=cpNativeSolarNormal*(CPNativeOrbitLimits.y+point.y);
                // CP_SURFACE_VERTEX
                // Bias mask lookup into the emitting tile; projection uses the original point.
                if(cpNativeRepeat>0.5&&cpNativeSide>0.5){if(cpNativeSide<1.5)cpNativeSourcePoint.x+=0.02;else if(cpNativeSide<2.5)cpNativeSourcePoint.x-=0.02;else if(cpNativeSide<3.5)cpNativeSourcePoint.z+=0.02;else cpNativeSourcePoint.z-=0.02;}
                if(CPNativeEye.y>160.0){
                    cpNativeFacing=dot(cpNativeDirection(point.xz),CPNativeOrbitNormal)-CPNativeOrbitLimits.w;
                    // What is by the eye is drawn as it is built: the planet is rounded far off, and whatever carries the
                    // observer up here (a ship's deck, a seat, a mount) is not planet. The lens widens the level by
                    // (radius+height)/radius; within reach of the eye that would stretch the deck underfoot.
                    vec3 rigid=point+CPNativeOrbitOffset;
                    point=mix(rigid,cpNativeOrbitRepair(point),smoothstep(96.0,256.0,length(relative)));
                    cpNativeFacing=min(cpNativeFacing,CPNativeOrbitLimits.z-length(point.xz-CPNativeEye.xz));
                }else{point=CPNativeFlatOrigin+point.x*CPNativeFlatU+point.z*CPNativeFlatV+vec3(0,point.y,0);if(CPNativeAirLimits.x>0.0)cpNativeFacing=dot(cpNativeAirDirection(point.xz),CPNativeAirNormal);point.xz=cpNativeRepair(point.xz);cpNativeFacing=min(cpNativeFacing,CPNativeGapLimits.z-length(point.xz-CPNativeEye.xz));}
                cpNativeRelative=point-CPNativeEye;
                relative=point-CPNativeEye-CPNativeViewOffset;
                view.xyz=CPNativeWorldX*relative.x+CPNativeWorldY*relative.y+CPNativeWorldZ*relative.z;
                // Tile closure walls sit under equal neighbour tops and depth-fight them as flickering grid
                // lines once depth precision (~d²) exceeds a pixel. Scaling along the eye ray keeps every pixel
                // but pushes the wall past the tie; behind a wall is only its own solid column.
                if(cpNativeVoxel>0.5&&cpNativeSide>0.5)view.xyz*=1.0+max(0.002,3e-6*length(view.xyz));
                gl_Position=cpNativeProject(view);
                gl_Position.xy+=screenOffset*gl_Position.w;
                cpNativeRangeFog=smoothstep(CPNativeRange.x,CPNativeRange.y,length(point.xz-CPNativeEye.xz))*smoothstep(128.0,160.0,CPNativeEye.y);
                // CP_NATIVE_FOG
                // CP_NATIVE_CLIP
            }
            """;

    private GroundShaderSource() {}
}
