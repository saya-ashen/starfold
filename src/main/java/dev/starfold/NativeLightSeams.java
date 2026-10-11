package dev.starfold;

/** Light propagation re-addressing for face seams; see NativeGroundLightSeamMixin. */
public final class NativeLightSeams {
    /** Implemented by every LightEngine: the Native layout it lights, or null for other levels and clients. */
    public interface Engine { NativePlanetAccess starfold$seams(); }
    public static long block(Object engine,long pos){var access=((Engine)engine).starfold$seams();return access==null?pos:NativeChunkViews.alias(access,pos);}
    /** A halo position whose owner section holds no light data (a corner or a just-unloaded owner) reads dark and takes no writes. */
    public static int get(Object engine,Object storage,long pos,java.util.function.LongToIntFunction original){
        long owner=block(engine,pos);if(owner!=pos&&!((dev.starfold.mixin.NativeGroundLightStorageAccess)storage).starfold$storing(net.minecraft.core.SectionPos.blockToSection(owner)))return 0;
        return original.applyAsInt(owner);
    }
    public static void set(Object engine,Object storage,long pos,int level,java.util.function.LongConsumer original){
        long owner=block(engine,pos);if(owner!=pos&&!((dev.starfold.mixin.NativeGroundLightStorageAccess)storage).starfold$storing(net.minecraft.core.SectionPos.blockToSection(owner)))return;
        original.accept(owner);
    }
    public static long section(Object engine,long section){var access=((Engine)engine).starfold$seams();return access==null?section:NativeChunkViews.aliasSection(access,section);}
    private NativeLightSeams(){}
}
