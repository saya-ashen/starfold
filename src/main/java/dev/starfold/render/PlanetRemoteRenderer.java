package dev.starfold.render;

import static dev.starfold.render.RemotePlanetMeshes.spheres;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import dev.starfold.DevelopmentHooks;
import dev.starfold.api.RenderWorkDiagnostics;
import dev.starfold.api.RenderWorkDiagnostics.Metric;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.flight.GroundFlightClient;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.planet.Anomaly;
import dev.starfold.sky.Space;
import dev.starfold.space.RenderFrame;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceView;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;

import java.util.*;

/**
 * Every other planet of the system, each derived from its saved generator and actual indexed column
 * surfaces.
 */
public final class PlanetRemoteRenderer {
    private static final Map<Integer, RemotePlanetMeshes> bodies = new HashMap<>();

    private static final Map<Integer, VertexBuffer> impostors = new HashMap<>();

    /** Latest received deterministic profile, without exposing renderer meshes or ownership. */
    public static PlanetTerrain terrain(int planet) {
        if (level != Minecraft.getInstance().level) return null;
        var body = bodies.get(planet);
        return body == null ? null : body.view.terrain();
    }

    private static Object level;
    private static int turn;
    private static long clock;
    private static int regionDrawn;
    private static Boolean ownLoadingCover;

    /**
     * Client ticks without word of a planet before it is dropped; the server speaks of each every
     * second.
     */
    private static final int SILENCE = 200;

    private static long ownTold = Long.MIN_VALUE / 2;

    public static void initialize() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post cpEvent) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (level != null && level != mc.level) clear();
                    clock++;
                    // A planet the server no longer tells of has fallen out of the nearest: its
                    // meshes are given up.
                    // The planet arrived at is told of no longer; what it was drawn with is kept
                    // until its ground is shown.
                    bodies.entrySet()
                            .removeIf(
                                    e -> {
                                        var body = e.getValue();
                                        if (clock - body.heard <= SILENCE
                                                || SpaceClient.frame() == null
                                                        && e.getKey() == GroundClient.planet()
                                                        && !GroundRenderer.remoteReplacementReady())
                                            return false;
                                        body.close();
                                        return true;
                                    });
                });
        StarfoldNetwork.clientReceiver(
                PlanetRemotePackets.Summary.TYPE,
                (p, c) -> {
                    if (!accepts(p.profile().session(), p.planet())) return;
                    var expectedWorld = Minecraft.getInstance().level;
                    var known = level == expectedWorld ? bodies.get(p.planet()) : null;
                    GroundViewClient.resolveProfile(
                            p.profile(),
                            p.planet(),
                            known == null ? null : known.view.profile(),
                            resolved -> {
                                if (Minecraft.getInstance().level != expectedWorld
                                        || !accepts(resolved.session(), p.planet())) return;
                                var packet =
                                        new PlanetRemotePackets.Summary(
                                                p.planet(),
                                                resolved,
                                                p.revision(),
                                                p.changed(),
                                                p.unknown());
                                var world = net.minecraft.client.Minecraft.getInstance().level;
                                if (world == null
                                        || !PlanetTint.known(
                                                packet.profile(), world.registryAccess())) return;
                                identity(
                                        net.minecraft.client.Minecraft.getInstance().level,
                                        p.profile().session());
                                var body =
                                        bodies.computeIfAbsent(
                                                p.planet(), k -> new RemotePlanetMeshes());
                                body.heard = clock;
                                var mask = mask(p.profile().session());
                                if (mask != null) body.mask = mask;
                                body.view.summary(packet);
                            });
                });
        StarfoldNetwork.clientReceiver(
                PlanetRemotePackets.Surface.TYPE,
                (p, c) -> {
                    var body = bodies.get(p.planet());
                    if (body != null
                            && accepts(p.surface().session(), p.planet())
                            && net.minecraft.client.Minecraft.getInstance().level == level)
                        body.view.surface(p);
                });
    }

    private static void identity(Object current, long session) {
        if (level != current) {
            clear();
            level = current;
        } else for (var body : bodies.values()) body.session(session);
    }

    public static void clear() {
        for (var body : bodies.values()) body.close();
        bodies.clear();
        impostors.values().forEach(VertexBuffer::close);
        impostors.clear();
        level = null;
        regionDrawn = 0;
    }

    /** Dev probe only: how many of the other planets heard of have a mesh to be drawn with. */
    public static String probeReading() {
        int shown = 0;
        for (var body : bodies.values()) {
            var drawn = body.resident != null ? body.resident : body.fallback;
            if (drawn != null && drawn.planet() != GroundClient.planet()) shown++;
        }
        return "remote "
                + shown
                + " of "
                + bodies.size()
                + " steps "
                + bodies.entrySet().stream()
                        .map(
                                e ->
                                        e.getKey()
                                                + ":"
                                                + e.getValue().residentStep
                                                + "->"
                                                + e.getValue().requestedStep)
                        .toList()
                + " own "
                + (bodies.containsKey(GroundClient.planet())
                        ? bodies.get(GroundClient.planet()).resident != null ? "built" : "building"
                        : "none");
    }

    /** A fixed share of each frame builds meshes; the planets take turns at being first to it. */
    private static void work() {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.REMOTE_WORK);
        try {
            long deadline = System.nanoTime() + 2_000_000;
            if (bodies.isEmpty()) return;
            turn = (turn + 1) % bodies.size();
            var order = bodies.entrySet().iterator();
            for (int i = 0; i < turn; i++) order.next();
            var mc = Minecraft.getInstance();
            var model = GroundClient.ephemeris();
            var eye =
                    SpaceClient.active()
                            ? SpaceClient.universeEye(mc.gameRenderer.getMainCamera().getPosition())
                            : GroundClient.universeEye();
            double time = SpaceClient.active() ? SpaceClient.time() : GroundClient.time();
            for (int i = 0; i < bodies.size(); i++) {
                if (!order.hasNext()) order = bodies.entrySet().iterator();
                var entry = order.next();
                var body = entry.getValue();
                var terrain = body.view.terrain();
                if (terrain == null || body.view.session() == 0) continue;
                double distance = model.at(entry.getKey(), time).center().sub(eye).length();
                body.requestedStep =
                        GroundFarMesh.remoteLevel(
                                Math.max(0, distance - terrain.shape().radius()),
                                body.requestedStep,
                                GroundMeshScheduler.lodFov,
                                mc.getWindow().getHeight());
                // Finish a frozen cohort before following a changing zoom; the resident stays
                // visible.
                body.work(deadline);
            }
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.REMOTE_WORK);
        }
    }

    private static void vector(ShaderInstance shader, String name, Point p) {
        shader.getUniform(name).set((float) p.x(), (float) p.y(), (float) p.z());
    }

    public static void draw(RenderFrame c) {
        dev.starfold.api.RenderTimings.begin(dev.starfold.api.RenderTimings.Section.REMOTE);
        try {
            if (SpaceClient.active()) {
                drawRegion(c);
                return;
            }
            if (!GroundClient.ready()) return;
            identity(
                    Minecraft.getInstance().level,
                    GroundFlightClient.ready()
                            ? GroundFlightClient.session()
                            : SpaceClient.retainedSession());
            // A vessel high above its planet may leave it for another: the planet is meshed as
            // another
            // planet would be while it is still the vessel's own,
            // from what the client holds of it, so that it is there to be seen the moment it is
            // left.
            if (GroundClient.eye().y() >= GroundOrbit.END
                    && GroundViewClient.coverageReady()
                    && GroundViewClient.profile() != null
                    && GroundViewClient.profile().session() == GroundFlightClient.session()) {
                var body =
                        bodies.computeIfAbsent(
                                GroundClient.planet(), k -> new RemotePlanetMeshes());
                body.heard = clock;
                body.mask = mask(GroundViewClient.profile().session());
                if (clock - ownTold >= 100 || body.view.session() == 0) {
                    ownTold = clock;
                    body.view.own(
                            GroundViewClient.profile(),
                            GroundClient.planet(),
                            GroundViewClient.changed(),
                            GroundViewClient.overlays());
                }
            }
            if (c.event().getStage()
                    == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY)
                work();
            var shader = GroundExterior.shader();
            if (shader == null) return;
            var pass = new RenderPassState(false, false, false);
            try {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.disableCull();
                RenderSystem.disableBlend();
                RenderSystem.setShader(() -> shader);
                boolean sky = c.event().getStage()
                        == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY;
                boolean loadingCover = !sky && coversPartialPlanet();
                boolean ownReplacementReady = !loadingCover && GroundRenderer.remoteReplacementReady();
                for (var body : bodies.values()) {
                    // A planet is drawn with the meshes it had until its next are built: the one
                    // arrived at until the ground is shown, any other until then.
                    if (body.fallback != null
                            && (body.fallback.planet() == GroundClient.planet()
                                    ? !ownReplacementReady
                                    : body.resident == null))
                        probeBody(c, shader, body.fallback, body.fallbackMeshes, body.fallbackMask);
                    if (body.resident != null
                            && (body.resident.planet() != GroundClient.planet()
                                    || body.fallback == null && !ownReplacementReady))
                        probeBody(c, shader, body.resident, body.meshes, body.mask);
                }
                if (sky) drawGroundImpostors(c, shader);
                else if (loadingCover) {
                    int planet = GroundClient.planet();
                    var body = bodies.get(planet);
                    if (body == null || (body.resident == null && body.fallback == null))
                        drawGroundImpostor(c, shader, planet);
                }
            } finally {
                RenderPassState.finish(
                        () -> {
                            dev.starfold.api.PlanetSurfaceRender.exteriorContext(null);
                            shader.getUniform("BodyFade").set(1f);
                            shader.getUniform("OccluderRadius").set(0f);
                            shader.getUniform("ShadeRadius").set(0f);
                            GasLighting.clear(shader);
                        },
                        pass::close);
            }
        } finally {
            dev.starfold.api.RenderTimings.end(dev.starfold.api.RenderTimings.Section.REMOTE);
        }
    }

    /**
     * A mask belongs to the view's session; retained bodies keep theirs after that observer has
     * gone.
     */
    private static GroundSpace.Config mask(long session) {
        if (session != 0 && session == SpaceClient.session()) return SpaceClient.mask();
        return session != 0 && GroundFlightClient.ready() && session == GroundFlightClient.session()
                ? GroundFlightClient.mask()
                : null;
    }

    private static void probeBody(
            RenderFrame c,
            ShaderInstance shader,
            PlanetRemoteView.Snapshot snapshot,
            VertexBuffer[] buffers,
            GroundSpace.Config mask) {
        if (GroundClient.model().body(snapshot.planet()).appearance()
                != GroundEphemeris.Appearance.SURFACE) return;
        if (DevelopmentHooks.arrival.enabled()
                && snapshot.planet() == GroundClient.planet()
                && Arrays.stream(buffers).anyMatch(Objects::nonNull))
            DevelopmentHooks.remoteDraw = true;
        boolean sky =
                c.event().getStage()
                        == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage
                                .AFTER_SKY;
        if (sky == (snapshot.planet() != GroundClient.planet()))
            drawBody(c, shader, snapshot, buffers, mask);
    }

    private static void drawBody(
            RenderFrame c,
            ShaderInstance shader,
            PlanetRemoteView.Snapshot snapshot,
            VertexBuffer[] buffers,
            GroundSpace.Config mask) {
        if (RenderWorkDiagnostics.active())
            RenderWorkDiagnostics.add(Metric.REMOTE_BODY_ATTEMPTS, 1);
        dev.starfold.api.PlanetSurfaceRender.exteriorContext(
                new dev.starfold.api.PlanetSurfaceRender.Context(
                        GroundClient.model(),
                        snapshot.planet(),
                        GroundClient.time(),
                        null,
                        snapshot.terrain().terrainPlan()));
        var home = GroundClient.home();
        var target = GroundClient.model().at(snapshot.planet(), GroundClient.time());
        var eye = GroundClient.eye();
        var orbit = GroundClient.orbit();
        var f = orbit.observerFrame;
        boolean remote = snapshot.planet() != GroundClient.planet();
        double scale =
                remote
                        ? SpaceView.renderScale(
                                target.center().sub(GroundClient.universeEye()).length())
                        : 1;
        var drawCenter = groundCenter(target.center(), scale);
        var chartU = target.inverseVector(home.vector(f.u())).scale(scale);
        var chartUp = target.inverseVector(home.vector(f.up())).scale(scale);
        var chartV = target.inverseVector(home.vector(f.v())).scale(scale);
        var bodyEye = target.local(GroundClient.universeEye());
        var occluder = target.local(home.center());
        vector(shader, "ChartU", chartU);
        vector(shader, "ChartUp", chartUp);
        vector(shader, "ChartV", chartV);
        vector(shader, "DrawCenter", drawCenter);
        vector(
                shader,
                "SunBody",
                target.local(GroundClient.model().star(target.center()).position()));
        var grade = GroundAtmosphere.grade();
        // Preparation runs before apply: retain the managed value, but do not upload to program 0.
        vector(shader, "AtmosphereGrade", grade);
        vector(shader, "BodyEye", bodyEye);
        vector(shader, "Occluder", occluder);
        shader.getUniform("OccluderRadius")
                .set(remote ? (float) GroundClient.shape().radius() : 0f);
        skyColour(shader, remote, c);
        shade(shader, GroundClient.model(), snapshot.planet(), GroundClient.time());
        GroundClient.lensUniforms(shader);
        var matrix =
                GroundClient.place(
                        new Matrix4f(c.positionMatrix()),
                        GroundClient.face(),
                        c.camera().getPosition());
        double core = PlanetShape.coreRadius(snapshot.planet()), extent = 0;
        boolean bounded = true;
        if (remote)
            for (var gpu : buffers) {
                if (gpu == null) continue;
                var s = spheres.get(gpu);
                if (s == null) {
                    bounded = false;
                    break;
                }
                extent =
                        Math.max(extent, Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]) + s[3]);
            }
        // The shader discards every fragment behind the occluder sphere; skip a body wholly there.
        boolean drawn =
                !(remote
                        && bounded
                        && extent > 0
                        && hidden(
                                0, 0, 0, extent, bodyEye, occluder, GroundClient.shape().radius()));
        boolean diagnostics = RenderWorkDiagnostics.active();
        long patchDraws = 0, coreRejects = 0;
        if (diagnostics && !drawn) RenderWorkDiagnostics.add(Metric.REMOTE_BODY_REJECTS, 1);
        // Surface scope includes patch selection and submission, but excludes body setup and rim.
        long surfaceTimer = RenderWorkDiagnostics.start();
        try {
            if (drawn) {
                RemoteOcclusion coreOcclusion = null;
                for (int patch = 0; patch < buffers.length; patch++) {
                    var gpu = buffers[patch];
                    if (gpu == null
                            || !keepRemotePatch(
                                    remote, !remote && !coversPartialPlanet() && GroundRenderer.remotePatchReady(patch)))
                        continue;
                    var s = spheres.get(gpu);
                    // Behind the solid core the body's own surface covers the patch.
                    if (s != null) {
                        if (coreOcclusion == null)
                            coreOcclusion =
                                    new RemoteOcclusion(bodyEye, GroundEphemeris.ZERO, core);
                        if (coreOcclusion.hidden(s[0], s[1], s[2], s[3])) {
                            if (diagnostics) coreRejects++;
                            continue;
                        }
                    }
                    gpu.bind();
                    try {
                        gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
                        if (diagnostics) patchDraws++;
                    } finally {
                        VertexBuffer.unbind();
                    }
                }
            }
        } finally {
            RenderWorkDiagnostics.finish(Metric.REMOTE_SURFACE_NS, surfaceTimer);
            if (diagnostics) {
                RenderWorkDiagnostics.add(Metric.REMOTE_PATCH_DRAWS, patchDraws);
                RenderWorkDiagnostics.add(Metric.REMOTE_PATCH_CORE_REJECTS, coreRejects);
            }
        }
        GroundAtmosphere.rim(
                c,
                GroundClient.model(),
                snapshot.planet(),
                GroundClient.time(),
                bodyEye,
                matrix,
                drawCenter,
                chartU,
                chartUp,
                chartV,
                snapshot.terrain().shape().radius());
    }

    /** Partial chart coverage only replaces the corresponding patch of the retained own body. */
    static boolean keepRemotePatch(boolean otherPlanet, boolean chartPatchReady) {
        return otherPlanet || !chartPatchReady;
    }

    /**
     * Whether a sphere (x, y, z, radius) lies wholly in the shadow of {@code occluder}'s sphere seen from
     * {@code eye}: inside the tangent cone and beyond the plane through the occluder's centre, where every
     * sight line to it crosses the occluder first.
     */
    static boolean hidden(
            double x, double y, double z, double radius, Point eye, Point occluder, double size) {
        return new RemoteOcclusion(eye, occluder, size).hidden(x, y, z, radius);
    }

    private static void skyColour(ShaderInstance shader, boolean remote, RenderFrame c) {
        var sky =
                GroundAtmosphere.enabled()
                        ? GroundAtmosphere.fog()
                        : GroundSolar.sky(GroundClient.elevation(), GroundClient.darkness());
        vector(shader, "SkyColor", sky);
        float rain =
                Minecraft.getInstance()
                        .level
                        .getRainLevel(c.tickCounter().getGameTimeDeltaPartialTick(false));
        shader.getUniform("BodyFade")
                .set(
                        remote
                                ? (float)
                                                (GroundAtmosphere.enabled()
                                                        ? 1
                                                                - dev.starfold.sky
                                                                                .AtmosphereSettings
                                                                                .REMOTE_HAZE
                                                                        * (1
                                                                                - GroundAtmosphere
                                                                                        .stars())
                                                        : SpaceView.bodyFade(
                                                                GroundClient.elevation(),
                                                                GroundClient.darkness()))
                                        * (1 - rain)
                                : 1f);
    }

    /** The parent sphere casts a shadow on the existing mesh, not a second moon image. */
    private static void shade(
            ShaderInstance shader, GroundEphemeris.Model model, int planet, double tick) {
        GasLighting.apply(shader, model, model.at(planet, tick), tick, -1);
        int parent =
                model.body(planet).motion() instanceof GroundEphemeris.Orbit orbit
                        ? orbit.parent()
                        : -1;
        var parentBody = parent < 0 ? null : model.bodies().get(parent);
        float radius =
                parentBody == null
                        ? 0
                        : parentBody.slot() >= 0
                                ? (float) PlanetShape.of(parentBody.slot()).radius()
                                : parentBody.appearance() == GroundEphemeris.Appearance.GAS
                                        ? (float) parentBody.radius()
                                        : 0;
        shader.getUniform("ShadeRadius").set(radius);
        vector(
                shader,
                "ShadeCenter",
                radius == 0
                        ? GroundEphemeris.ZERO
                        : model.at(planet, tick).local(model.frame(parent, tick).center()));
    }

    public static boolean acceptsPlanSession(long session) {
        boolean space = SpaceClient.present();
        return dev.starfold.universe.PlanetPlanSync.acceptsSession(
                session,
                GroundFlightClient.ready() ? GroundFlightClient.session() : 0,
                space ? SpaceClient.session() : 0,
                !space && GroundClient.ready() && !GroundRenderer.remoteReplacementReady()
                        ? SpaceClient.retainedSession()
                        : 0);
    }

    private static boolean accepts(long session, int planet) {
        if (!acceptsPlanSession(session)) return false;
        if (SpaceClient.present()) return session != 0 && session == SpaceClient.session();
        if (GroundClient.ready()
                && planet == GroundClient.planet()
                && !GroundRenderer.remoteReplacementReady()
                && session != 0
                && session == SpaceClient.retainedSession()) return true;
        return GroundFlightClient.ready()
                && session == GroundFlightClient.session()
                && planet != GroundClient.planet();
    }

    /** Seed the remote source on departure; retain already built remote meshes across the handoff. */
    public static void departing() {
        var profile = GroundViewClient.profile();
        int planet = GroundViewClient.cachedPlanet();
        if (profile == null || planet < 0) return;
        identity(Minecraft.getInstance().level, profile.session());
        var body = bodies.computeIfAbsent(planet, k -> new RemotePlanetMeshes());
        body.mask = mask(profile.session());
        body.view.own(profile, planet, GroundViewClient.changed(), GroundViewClient.overlays());
        body.heard = clock;

    }

    /**
     * The passive summary already carries the own-view profile, coverage and bounded surface
     * stream.
     */
    static void seedOwn(int planet, long session) {
        var body = bodies.get(planet);
        if (level != Minecraft.getInstance().level
                || body == null
                || body.view.session() == 0
                || body.view.session() != SpaceClient.retainedSession()) return;
        var snapshot = body.view.snapshot();
        if (snapshot != null) GroundViewClient.preview(session, snapshot);
    }

    public static void beginFrame() {
        regionDrawn = 0;
        ownLoadingCover = null;
    }

    // Both opaque passes use the same decision even if a mesh publishes between them.
    static boolean coversPartialPlanet() {
        if (ownLoadingCover == null) {
            ownLoadingCover = GroundExterior.shader() != null
                    && !SpaceClient.active()
                    && GroundClient.ready()
                    && GroundClient.eye().y() >= GroundOrbit.END
                    && GroundClient.model().body(GroundClient.planet()).appearance()
                            == GroundEphemeris.Appearance.SURFACE
                    && !GroundRenderer.visiblePlanetReady();
        }
        return ownLoadingCover;
    }

    public static int regionDrawn() {
        return regionDrawn;
    }

    static Point groundCenter(Point world, double scale) {
        var eye = GroundClient.eye();
        var orbit = GroundClient.orbit();
        var f = orbit.observerFrame;
        var offset = GroundClient.home().local(world);
        var center =
                new Point(eye.x(), -GroundClient.shape().radius(), eye.z())
                        .add(orbit.offset)
                        .add(new Point(offset.dot(f.u()), offset.dot(f.up()), offset.dot(f.v())));
        return eye.add(center.sub(eye).scale(scale));
    }

    private static void drawGroundImpostors(RenderFrame c, ShaderInstance shader) {
        for (int index = 0; index < GroundClient.model().count(); index++) {
            if (index == GroundClient.planet()) continue;
            var remote = bodies.get(index);
            if (remote != null) {
                var snapshot = remote.resident != null ? remote.resident : remote.fallback;
                var buffers = remote.resident != null ? remote.meshes : remote.fallbackMeshes;
                if (snapshot != null && Arrays.stream(buffers).anyMatch(Objects::nonNull)) continue;
            }
            drawGroundImpostor(c, shader, index);
        }
    }

    private static void drawGroundImpostor(RenderFrame c, ShaderInstance shader, int index) {
        var model = GroundClient.model();
        var eye = GroundClient.universeEye();
        var home = GroundClient.home();
        var f = GroundClient.orbit().observerFrame;
        var matrix = GroundClient.place(
                new Matrix4f(c.positionMatrix()), GroundClient.face(), c.camera().getPosition());
        boolean remote = index != GroundClient.planet();
        var target = model.at(index, GroundClient.time());
        double scale = remote ? SpaceView.renderScale(target.center().sub(eye).length()) : 1;
        double radius = PlanetShape.of(index).radius();
        var chartU = target.inverseVector(home.vector(f.u())).scale(scale);
        var chartUp = target.inverseVector(home.vector(f.up())).scale(scale);
        var chartV = target.inverseVector(home.vector(f.v())).scale(scale);
        var center = groundCenter(target.center(), scale);
        var bodyEye = target.local(eye);
        long impostorTimer = RenderWorkDiagnostics.start();
        try {
            vector(shader, "ChartU", chartU);
            vector(shader, "ChartUp", chartUp);
            vector(shader, "ChartV", chartV);
            vector(shader, "DrawCenter", center);
            vector(shader, "SunBody", target.local(model.star(target.center()).position()));
            vector(shader, "BodyEye", bodyEye);
            vector(shader, "Occluder", target.local(home.center()));
            vector(shader, "AtmosphereGrade", GroundAtmosphere.grade());
            shader.getUniform("OccluderRadius").set(remote ? (float) GroundClient.shape().radius() : 0f);
            skyColour(shader, remote, c);
            shade(shader, model, index, GroundClient.time());
            GroundClient.lensUniforms(shader);
            var gpu = impostors.computeIfAbsent(index, key -> impostor(radius));
            gpu.bind();
            try {
                gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
                if (RenderWorkDiagnostics.active())
                    RenderWorkDiagnostics.add(Metric.REMOTE_IMPOSTOR_DRAWS, 1);
            } finally {
                VertexBuffer.unbind();
            }
        } finally {
            RenderWorkDiagnostics.finish(Metric.REMOTE_IMPOSTOR_NS, impostorTimer);
        }
        GroundAtmosphere.rim(
                c, model, index, GroundClient.time(), bodyEye, matrix, center,
                chartU, chartUp, chartV, radius);
    }

    private static VertexBuffer impostor(double radius) {
        try (var out = new GroundExteriorVertices()) {
            int latitudes = 8, longitudes = 16;
            for (int y = 0; y < latitudes; y++)
                for (int x = 0; x < longitudes; x++) {
                    var points = new ArrayList<Point>(4);
                    for (int[] corner :
                            new int[][] {{x, y}, {x + 1, y}, {x + 1, y + 1}, {x, y + 1}}) {
                        double a = corner[0] * 2 * Math.PI / longitudes;
                        double b = corner[1] * Math.PI / latitudes;
                        points.add(
                                new Point(
                                                Math.sin(b) * Math.cos(a),
                                                Math.cos(b),
                                                Math.sin(b) * Math.sin(a))
                                        .scale(radius));
                    }
                    // Provisional neutral fallback until a detailed surface mesh is resident.
                    out.quad(points, 0x777777, 255, true);
                }
            var gpu = new VertexBuffer(VertexBuffer.Usage.STATIC);
            gpu.bind();
            try {
                gpu.upload(out.build());
                return gpu;
            } catch (RuntimeException failure) {
                gpu.close();
                throw failure;
            } finally {
                VertexBuffer.unbind();
            }
        }
    }

    /** Ordinary camera-relative Level geometry. The ship never enters this mesh path. */
    private static void drawRegion(RenderFrame c) {
        long session = SpaceClient.session();
        if (session == 0) return;
        identity(Minecraft.getInstance().level, session);
        work();
        var shader = GroundExterior.shader();
        if (shader == null) return;
        var pass = new RenderPassState(false, false, false);
        var eye = SpaceClient.universeEye(c.camera().getPosition());
        var frame = SpaceClient.frame();
        var model = GroundClient.ephemeris();
        double tick = SpaceClient.time();
        var matrix = new Matrix4f(c.positionMatrix());
        try {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShader(() -> shader);
            SpaceClient.lens(shader);
            shader.getUniform("BodyFade").set(1f);
            shader.getUniform("AtmosphereGrade").set(1f, 1f, 1f);
            shader.getUniform("OccluderRadius").set(0f);
            for (var body : bodies.values()) {
                if (RenderWorkDiagnostics.active())
                    RenderWorkDiagnostics.add(Metric.REMOTE_BODY_ATTEMPTS, 1);
                var snapshot = body.resident != null ? body.resident : body.fallback;
                if (snapshot == null
                        || snapshot.planet() >= model.count()
                        || model.body(snapshot.planet()).appearance()
                                != GroundEphemeris.Appearance.SURFACE) {
                    if (RenderWorkDiagnostics.active())
                        RenderWorkDiagnostics.add(Metric.REMOTE_BODY_REJECTS, 1);
                    continue;
                }
                var target = model.at(snapshot.planet(), tick);
                var placement = SpaceView.place(frame, eye, target);
                double radius =
                        snapshot.terrain().shape().radius()
                                + Math.max(512, SpaceClient.mask().height() + Anomaly.BEAM_HEIGHT);
                if (!SpaceView.visible(placement.center().length(), radius)) {
                    if (RenderWorkDiagnostics.active())
                        RenderWorkDiagnostics.add(Metric.REMOTE_BODY_REJECTS, 1);
                    continue;
                }
                double scale = SpaceView.renderScale(placement.center().length());
                vector(shader, "ChartU", placement.u().scale(scale));
                vector(shader, "ChartUp", placement.up().scale(scale));
                vector(shader, "ChartV", placement.v().scale(scale));
                vector(shader, "DrawCenter", placement.center().scale(scale));
                dev.starfold.api.PlanetSurfaceRender.exteriorContext(
                        new dev.starfold.api.PlanetSurfaceRender.Context(
                                model,
                                snapshot.planet(),
                                tick,
                                null,
                                snapshot.terrain().terrainPlan()));
                vector(shader, "SunBody", target.local(model.star(target.center()).position()));
                shade(shader, model, snapshot.planet(), tick);
                boolean drawn = false;
                boolean diagnostics = RenderWorkDiagnostics.active();
                long patchDraws = 0;
                long surfaceTimer = RenderWorkDiagnostics.start();
                try {
                    for (var gpu : body.resident != null ? body.meshes : body.fallbackMeshes) {
                        if (gpu == null) continue;
                        gpu.bind();
                        try {
                            gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
                            drawn = true;
                            if (diagnostics) patchDraws++;
                        } finally {
                            VertexBuffer.unbind();
                        }
                    }
                } finally {
                    RenderWorkDiagnostics.finish(Metric.REMOTE_SURFACE_NS, surfaceTimer);
                    if (diagnostics)
                        RenderWorkDiagnostics.add(Metric.REMOTE_PATCH_DRAWS, patchDraws);
                }
                if (drawn) {
                    GroundAtmosphere.rim(
                            c,
                            model,
                            snapshot.planet(),
                            tick,
                            target.local(eye),
                            matrix,
                            placement.center().scale(scale),
                            placement.u().scale(scale),
                            placement.up().scale(scale),
                            placement.v().scale(scale),
                            snapshot.terrain().shape().radius());
                    regionDrawn++;
                    if (DevelopmentHooks.arrival.enabled()
                            && snapshot.planet() == Space.nearest(model, eye, tick))
                        DevelopmentHooks.remoteDraw = true;
                }
            }
            for (int index = 0; index < model.count(); index++) {
                var remote = bodies.get(index);
                var snapshot =
                        remote == null
                                ? null
                                : remote.resident != null ? remote.resident : remote.fallback;
                if (snapshot != null
                        && snapshot.planet() == index
                        && remote != null
                        && Arrays.stream(
                                        remote.resident != null
                                                ? remote.meshes
                                                : remote.fallbackMeshes)
                                .anyMatch(Objects::nonNull)) continue;
                GroundEphemeris.Frame placed;
                SpaceView.Placement placement;
                double scale;
                long impostorTimer = RenderWorkDiagnostics.start();
                try {
                    placed = model.at(index, tick);
                    placement = SpaceView.place(frame, eye, placed);
                    scale = SpaceView.renderScale(placement.center().length());
                    vector(shader, "ChartU", placement.u().scale(scale));
                    vector(shader, "ChartUp", placement.up().scale(scale));
                    vector(shader, "ChartV", placement.v().scale(scale));
                    vector(shader, "DrawCenter", placement.center().scale(scale));
                    vector(
                            shader,
                            "SunBody",
                            placed.local(model.star(placed.center(), tick).position()));
                    shade(shader, model, index, tick);
                    var gpu =
                            impostors.computeIfAbsent(
                                    index, key -> impostor(PlanetShape.of(key).radius()));
                    gpu.bind();
                    try {
                        gpu.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), shader);
                        if (RenderWorkDiagnostics.active())
                            RenderWorkDiagnostics.add(Metric.REMOTE_IMPOSTOR_DRAWS, 1);
                    } finally {
                        VertexBuffer.unbind();
                    }
                } finally {
                    RenderWorkDiagnostics.finish(Metric.REMOTE_IMPOSTOR_NS, impostorTimer);
                }
                GroundAtmosphere.rim(
                        c,
                        model,
                        index,
                        tick,
                        placed.local(eye),
                        matrix,
                        placement.center().scale(scale),
                        placement.u().scale(scale),
                        placement.up().scale(scale),
                        placement.v().scale(scale),
                        PlanetShape.of(index).radius());
                regionDrawn++;
            }
        } finally {
            RenderPassState.finish(
                    () -> {
                        dev.starfold.api.PlanetSurfaceRender.exteriorContext(null);
                        shader.getUniform("ShadeRadius").set(0f);
                        GasLighting.clear(shader);
                    },
                    pass::close);
        }
    }

    private PlanetRemoteRenderer() {}
}
