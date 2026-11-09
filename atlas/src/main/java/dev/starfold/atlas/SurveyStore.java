package dev.starfold.atlas;

import dev.starfold.api.PlanetMapApi;
import dev.starfold.api.PlanetFaces;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class SurveyStore {
    private record Entry(SurveyRecord record, Path path, boolean writable, int saved) {}
    private final Map<String, Entry> records = new HashMap<>();
    private Path directory;
    private int ticks;
    private PlanetMapApi.Observer last;
    private int lastRadius;
    SurveyRecord record(PlanetMapApi.Body body) {
        return records.computeIfAbsent(body.identity(), k -> {
            Path path = directory.resolve(k + ".survey");
            if (Files.exists(path)) {
                try (var in = new DataInputStream(Files.newInputStream(path))) {
                    var record = SurveyRecord.read(in, body.faceSize());
                    if (in.read() != -1) throw new IOException("Trailing survey data");
                    return new Entry(record, path, true, 0);
                } catch (IOException error) {
                    log("Cannot read survey; preserving original", path, error);
                    return new Entry(new SurveyRecord(body.faceSize()), path, false, 0);
                }
            }
            return new Entry(new SurveyRecord(body.faceSize()), path, true, 0);
        }).record();
    }
    void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { clear(); return; }
        Path next = directory(mc);
        if (!next.equals(directory)) { clear(); directory = next; }
        var observer = PlanetMapApi.observer();
        for (var body : PlanetMapApi.bodies()) {
            if (!body.surface()) continue;
            boolean seen = PlanetMapApi.seenFromSpace(body.index());
            boolean current = observer != null && observer.body() == body.index();
            if (!seen && !current) continue;
            var fog = record(body);
            if (seen) fog.reveal();
            int radius = mc.options.getEffectiveRenderDistance() * 16;
            if (!current || observer.inSpace()) continue;
            if (last != null && last.body() == observer.body() && last.face() == observer.face()
                    && (int) last.u() / 4 == (int) observer.u() / 4 && (int) last.v() / 4 == (int) observer.v() / 4
                    && lastRadius == radius) continue;
            // Reveal the loaded-distance neighbourhood, transporting cells across face seams.
            int u = (int) observer.u() / SurveyRecord.STEP * SurveyRecord.STEP;
            int v = (int) observer.v() / SurveyRecord.STEP * SurveyRecord.STEP;
            for (int dz = -radius; dz <= radius; dz += SurveyRecord.STEP)
                for (int dx = -radius; dx <= radius; dx += SurveyRecord.STEP) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    int x = u + dx, z = v + dz;
                    if (x < -body.faceSize() || z < -body.faceSize() || x >= 2 * body.faceSize() || z >= 2 * body.faceSize()) continue;
                    var p = PlanetFaces.adjacent(body.faceSize(), observer.face(), x, z);
                    fog.explore(p.face(), p.u() / SurveyRecord.STEP, p.v() / SurveyRecord.STEP);
                }
        }
        last = observer;
        lastRadius = mc.options.getEffectiveRenderDistance() * 16;
        if (++ticks % 100 == 0) save();
    }
    boolean available() { return directory != null; }
    void clear() { save(); records.clear(); directory = null; ticks = 0; last = null; }
    private void save() {
        for (var item : records.entrySet()) {
            var e = item.getValue();
            if (!e.writable() || e.saved() == e.record().revision()) continue;
            try {
                Files.createDirectories(e.path().getParent());
                Path temp = e.path().resolveSibling(e.path().getFileName() + ".tmp");
                try (var out = new DataOutputStream(Files.newOutputStream(temp))) { e.record().write(out); }
                try { Files.move(temp, e.path(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException error) { Files.move(temp, e.path(), StandardCopyOption.REPLACE_EXISTING); }
                item.setValue(new Entry(e.record(), e.path(), true, e.record().revision()));
            } catch (IOException error) { log("Cannot save survey", e.path(), error); }
        }
    }
    private static Path directory(Minecraft mc) {
        String world = mc.getSingleplayerServer() != null
                ? "local:" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                : "server:" + (mc.getCurrentServer() == null ? "lan:" + mc.getConnection().getConnection().getRemoteAddress() : mc.getCurrentServer().ip);
        try {
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (world + "|" + mc.level.dimension().location()).getBytes(StandardCharsets.UTF_8)));
            return mc.gameDirectory.toPath().resolve("starfold_atlas").resolve(id);
        } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    private static void log(String message, Path path, IOException error) {
        org.slf4j.LoggerFactory.getLogger("starfold_atlas").warn("{}: {}", message, path, error);
    }
}
