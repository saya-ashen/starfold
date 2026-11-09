package dev.starfold.render;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.worldgen.*;
import java.util.*;
/** Build complete current-format profiles for pure remote-view fixtures. */
public final class SurfaceProfiles {
 public static PlanetPackets.Profile current(PlanetPackets.Profile p){var g=p.generation();var policy=new PlanetGeneration(g.type(),g.surfaceVersion(),g.recipe(),g.size(),g.depth(),g.shell(),g.creatures(),p.surface().isPresent(),g.structures(),g.environment());
  var terrain=new PlanetTerrain(p.seed(),PlanetTerrain.VERSION,policy);var plan=p.plan().or(()->p.planReference().isPresent()?Optional.empty():Optional.of(terrain.plan()));
  return new PlanetPackets.Profile(p.session(),p.seed(),PlanetTerrain.VERSION,policy,p.biome(),p.surface(),plan,p.planReference());}
 private SurfaceProfiles(){}
}
