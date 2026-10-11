package dev.starfold;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;

/** How the regions of a temperate planet are shared out. Humidity moves the whole planet towards forest (above
 *  nought) or desert (below); temperature towards desert where it is dry (above) or away from it; latitude is how
 *  much colder the poles are than the equator, so that at nought regions lie as chance has them and the higher it is
 *  the more deserts keep to a belt about the equator. Land is how many blocks the whole of the ground stands higher
 *  (or lower) against the sea, which stays where it is: the more, the less of the planet is under it. */
public record NativePlanetClimate(double humidity,double temperature,double latitude,double land) {
    public NativePlanetClimate(double humidity,double temperature,double latitude){this(humidity,temperature,latitude,0);}
    public NativePlanetClimate withLand(double land){return new NativePlanetClimate(humidity,temperature,latitude,land);}
    public static final double MAX_LAND=40;
    /** Every temperate planet before climates were saved. */
    public static final NativePlanetClimate MILD=new NativePlanetClimate(0,0,.15);
    public static final Codec<NativePlanetClimate> CODEC=RecordCodecBuilder.create(i->i.group(
        Codec.doubleRange(-.5,.5).optionalFieldOf("humidity",0.0).forGetter(NativePlanetClimate::humidity),
        Codec.doubleRange(-.5,.5).optionalFieldOf("temperature",0.0).forGetter(NativePlanetClimate::temperature),
        Codec.doubleRange(0,1).optionalFieldOf("latitude",.15).forGetter(NativePlanetClimate::latitude),
        Codec.doubleRange(-MAX_LAND,MAX_LAND).optionalFieldOf("land",0.0).forGetter(NativePlanetClimate::land)
    ).apply(i,NativePlanetClimate::new));
    public NativePlanetClimate {if(!(humidity>=-.5&&humidity<=.5)||!(temperature>=-.5&&temperature<=.5)||!(latitude>=0&&latitude<=1)||!(land>=-MAX_LAND&&land<=MAX_LAND))throw new IllegalArgumentException("Planet climate");}
    public void write(FriendlyByteBuf buffer){buffer.writeDouble(humidity);buffer.writeDouble(temperature);buffer.writeDouble(latitude);buffer.writeDouble(land);}
    public static NativePlanetClimate read(FriendlyByteBuf buffer){return new NativePlanetClimate(buffer.readDouble(),buffer.readDouble(),buffer.readDouble(),buffer.readDouble());}
}
