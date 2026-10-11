package dev.starfold;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Celestial metadata only. Buildings, inventory and block entities stay in native chunk saves. */
public final class NativeGroundClock extends SavedData {
    public static final String FILE="starfold_native_ground_clock_v1";
    public final GroundEphemerisSave clock;
    public GroundSpace.Config mask=GroundSpace.DEFAULT_MASK;
    public GroundAir.Config air=GroundAir.DEFAULT;
    NativeGroundClock(GroundEphemerisSave clock){this.clock=clock;}
    static NativeGroundClock fresh(boolean exists){if(exists)throw new IllegalStateException("Existing native clock failed to load; refusing overwrite");var value=new NativeGroundClock(GroundEphemerisSave.fresh(false));value.setDirty();return value;}
    static NativeGroundClock load(CompoundTag tag,HolderLookup.Provider lookup){var value=new NativeGroundClock(GroundEphemerisSave.load(tag,lookup));if(!value.clock.model.sun().enabled())throw new IllegalArgumentException("Native solar model");if(tag.contains("native_mask")){if(!tag.contains("native_mask",10))throw new IllegalArgumentException("Native mask type");var m=tag.getCompound("native_mask");if(!m.contains("core",3)||!m.contains("height",3))throw new IllegalArgumentException("Native mask fields");value.mask=new GroundSpace.Config(m.getInt("core"),m.getInt("height"));}if(tag.contains("native_air")){if(!tag.contains("native_air",10))throw new IllegalArgumentException("Native air type");var a=tag.getCompound("native_air");if(!a.contains("start",3)||!a.contains("end",3))throw new IllegalArgumentException("Native air fields");value.air=new GroundAir.Config(a.getInt("start"),a.getInt("end"));}return value;}
    public static NativeGroundClock get(ServerLevel level){if(!NativeGroundServer.is(level))throw new IllegalArgumentException("Dimension");boolean multiple=NativePlanetSystem.multiple(level);var storage=NativePlanetSystem.authority(level).getDataStorage();var value=storage.computeIfAbsent(new Factory<>(()->{var fresh=fresh(((dev.starfold.mixin.PlanetSpaceDataAccess)storage).starfold$spaceDataFile(FILE).exists());if(!multiple)return fresh;
            // Old systems begin at sunrise. An authored sky starts at zero so its written phases mean what the author sees.
            var model=NativePlanetSystem.model(level);var begun=new NativeGroundClock(new GroundEphemerisSave(model,level.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator system&&system.starterShip().isPresent()?NativeGroundDaylight.midMorning(model,NativePlanetSystem.shape(level,0)):level.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator authored&&authored.authored()?0:NativeGroundDaylight.sunrise(model,NativePlanetSystem.shape(level,0))));begun.setDirty();return begun;},NativeGroundClock::load,null),FILE);if(multiple&&value.clock.model.count()<NativePlanetSystem.count(level)&&NativeUniverse.of(level)!=null){value.clock.model=NativePlanetSystem.model(level);value.setDirty();}
        if(value.clock.model.count()!=(multiple?NativePlanetSystem.count(level):1))throw new IllegalStateException("Saved native system/clock mismatch; refusing upgrade");return value;}
    public void advance(){clock.advance();setDirty();}
    public void skip(long ticks){clock.skip(ticks);setDirty();}
    public void spin(boolean on){clock.spin(on);setDirty();}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){var m=new CompoundTag();m.putInt("core",mask.core());m.putInt("height",mask.height());tag.put("native_mask",m);var a=new CompoundTag();a.putInt("start",air.start());a.putInt("end",air.end());tag.put("native_air",a);return clock.save(tag,lookup);}
}
