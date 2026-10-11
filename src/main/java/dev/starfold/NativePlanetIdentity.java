package dev.starfold;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.minecraft.resources.ResourceLocation;

/** Index is the position in the complete Shapes table, never in the near-profile cache. */
public record NativePlanetIdentity(Optional<ResourceLocation> type,String family,Optional<String> name) {
    public NativePlanetIdentity {java.util.Objects.requireNonNull(type);java.util.Objects.requireNonNull(name);if(!family.equals("temperate")&&!family.equals("rocky"))throw new IllegalArgumentException("Planet family");name.ifPresent(NativeSystemDefinition::validName);}
    static NativePlanetIdentity of(NativePlanetChunkGenerator planet){return new NativePlanetIdentity(planet.typeId(),planet.generation().type(),planet.displayName());}
    public String label(Function<String,String> translate){
        if(name.isPresent())return translate.apply(name.get());
        var id=type.orElseGet(()->ResourceLocation.fromNamespaceAndPath("starfold",family));
        String key="planet_type."+id.getNamespace()+"."+id.getPath();String value=translate.apply(key);return value.equals(key)?id.getPath():value;
    }
    /** Reserve letter and live cycle rank; only the variable name is ellipsized. */
    static String caption(int planet,String name,int rank,int count,int width,ToIntFunction<String> measure){
        String prefix=NativePlanetSystem.defaultName(planet)+" · ",suffix=" · "+rank+"/"+count;
        return prefix+ellipsis(name,Math.max(0,width-measure.applyAsInt(prefix+suffix)),measure)+suffix;
    }
    static String ellipsis(String text,int width,ToIntFunction<String> measure){
        if(measure.applyAsInt(text)<=width)return text;
        if(measure.applyAsInt("…")>width)return "";
        int end=text.length();while(end>0&&measure.applyAsInt(text.substring(0,end)+"…")>width)end=text.offsetByCodePoints(end,-1);
        return text.substring(0,end)+"…";
    }
}
