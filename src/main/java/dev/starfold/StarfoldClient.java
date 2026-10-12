package dev.starfold;

import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.dev.DevProbe;
import dev.starfold.flight.FlightClientHost;
import dev.starfold.flight.OrbitFlightControls;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.render.GroundClient;
import dev.starfold.render.GroundViewClient;
import dev.starfold.render.PlanetRemoteRenderer;
import dev.starfold.render.RendererGuard;
import dev.starfold.ship.AircraftDockRenderer;
import dev.starfold.ship.AirstairRenderer;
import dev.starfold.ship.ShipClientHost;
import dev.starfold.ship.SpacePackets;
import dev.starfold.ship.ThrusterRenderer;
import dev.starfold.ship.Thrusters;
import dev.starfold.sky.SkyHost;
import dev.starfold.space.FlightSnapshot;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceAttitudeClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceClientHost;
import dev.starfold.space.SpaceDust;
import dev.starfold.space.SpaceDustParticle;
import dev.starfold.waypoint.WaypointClient;
import dev.starfold.waypoint.WaypointClientHost;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "starfold", dist = Dist.CLIENT)
public final class StarfoldClient {
    public StarfoldClient(net.neoforged.bus.api.IEventBus modBus) {
        installSkyHost();
        installShipHost();
        installFlightHost();
        installSpaceHost();
        installWaypointHost();
        initializeClientSystems(modBus);
    }

    private static void installSkyHost() {
        SkyHost.installClient(
                new SkyHost() {
                    @Override
                    public boolean present() {
                        return SpaceClient.present();
                    }

                    @Override
                    public dev.starfold.geometry.BentStripGeometry.Point storage(
                            double x, double y, double z) {
                        return GroundClient.storage(x, y, z);
                    }
                });
    }

    private static void installShipHost() {
        ShipClientHost.install(
                new ShipClientHost() {
                    @Override
                    public boolean chartActive() {
                        return GroundClient.active();
                    }

                    @Override
                    public Point storage(double x, double y, double z) {
                        return GroundClient.storage(x, y, z);
                    }

                    @Override
                    public dev.starfold.planet.PlanetAccess chartAccess() {
                        return GroundClient.access();
                    }

                    @Override
                    public dev.starfold.ground.ObserverFrame chartFrame() {
                        return GroundClient.frame();
                    }

                    @Override
                    public void carriedObserver(dev.starfold.ground.ObserverFrame frame) {
                        GroundClient.frame(frame);
                    }

                    @Override
                    public boolean movingSurroundings() {
                        return SpaceClient.present();
                    }

                    @Override
                    public boolean mouseSteering() {
                        return SpaceAttitudeClient.mouseFlight();
                    }

                    @Override
                    public void carriedFrame(SpacePackets.Frame frame) {
                        SpaceClient.carried(frame);
                    }
                });
    }

    private static void installFlightHost() {
        FlightClientHost.install(
                new FlightClientHost() {
                    @Override
                    public void departing() {
                        PlanetRemoteRenderer.departing();
                    }

                    @Override
                    public boolean shared(Level level) {
                        return SpaceClient.shared(level);
                    }

                    @Override
                    public void sessionBound(ClientLevel level, long session) {
                        GroundViewClient.bindSession(level, session);
                    }

                    @Override
                    public void atmosphere(GroundAir.Config config) {
                        GroundClient.air(config);
                    }

                    @Override
                    public void metadata(GroundEphemeris.Model model, long tick) {
                        GroundClient.metadata(model, tick);
                    }

                    @Override
                    public boolean viewReady() {
                        return GroundClient.ready();
                    }

                    @Override
                    public boolean diagnosticHud() {
                        return GroundClient.DEBUG_HUD;
                    }

                    @Override
                    public Point chartPosition(int planet, PlanetShape.Pose pose) {
                        return GroundClient.plane(planet, pose);
                    }

                    @Override
                    public int chartTurns(Face face) {
                        return GroundClient.planeTurns(face);
                    }

                    @Override
                    public Point chartPoint(int planet, Face face, Point point) {
                        return GroundClient.planePoint(planet, face, point);
                    }
                });
    }

    private static void installSpaceHost() {
        SpaceClientHost.install(
                new SpaceClientHost() {
                    @Override
                    public GroundEphemeris.Model ephemeris() {
                        return GroundClient.ephemeris();
                    }

                    @Override
                    public double skyTime(float delta) {
                        return GroundClient.skyTime(delta);
                    }

                    @Override
                    public void viewLost() {
                        PlanetRemoteRenderer.clear();
                    }

                    @Override
                    public int sunLimit() {
                        return GroundClient.SUNS;
                    }

                    @Override
                    public int planet() {
                        return GroundClient.planet();
                    }

                    @Override
                    public void captureTarget(RenderFrame context) {
                        WaypointClient.capture(context);
                    }

                    @Override
                    public int launchPlanet() {
                        var launch = WaypointClient.active();
                        return launch == null ? -1 : launch.planet();
                    }

                    @Override
                    public FlightSnapshot.Home home() {
                        return WaypointClient.home();
                    }
                });
    }

    private static void installWaypointHost() {
        WaypointClientHost.install(
                new WaypointClientHost() {
                    @Override
                    public int planet() {
                        return GroundClient.planet();
                    }

                    @Override
                    public dev.starfold.ground.ObserverFrame frame() {
                        return GroundClient.frame();
                    }

                    @Override
                    public dev.starfold.planet.PlanetAccess access() {
                        return GroundClient.access();
                    }

                    @Override
                    public PlanetShape shape() {
                        return GroundClient.shape();
                    }

                    @Override
                    public dev.starfold.chart.GroundSpace.Pose pose() {
                        return GroundClient.pose();
                    }

                    @Override
                    public boolean ready() {
                        return GroundClient.ready();
                    }

                    @Override
                    public boolean diagnosticHud() {
                        return GroundClient.DEBUG_HUD;
                    }
                });
    }

    private static void initializeClientSystems(net.neoforged.bus.api.IEventBus modBus) {
        DevProbe.initialize();
        RendererGuard.initialize();
        OrbitFlightControls.initialize(modBus);
        GroundClient.initialize(modBus);
        AirstairRenderer.initialize(modBus);
        AircraftDockRenderer.initialize(modBus);
        modBus.addListener(
                (net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers
                                event) ->
                        event.registerBlockEntityRenderer(
                                thrusterType(), context -> new ThrusterRenderer()));
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) ->
                        event.registerSpriteSet(
                                SpaceDust.TYPE.get(),
                                sprites ->
                                        (type, level, x, y, z, dx, dy, dz) ->
                                                new SpaceDustParticle(level, x, y, z, sprites)));
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.world.level.block.entity.BlockEntityType<Thrusters.Thruster>
            thrusterType() {
        return (net.minecraft.world.level.block.entity.BlockEntityType<Thrusters.Thruster>)
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                        net.minecraft.resources.ResourceLocation.parse("starfold:thruster"));
    }
}
