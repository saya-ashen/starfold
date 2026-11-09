package dev.starfold.dev;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.starfold.api.SystemMapApi;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ship.HelmClient;
import dev.starfold.space.SpaceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Atlas assertions, visible UI and vanilla flight input for development acceptance. */
final class DevAtlasProbe {
    private static final Logger LOG = LoggerFactory.getLogger("starfold-probe");

    private static UUID approaching;
    private static double stopDistance;
    private static org.joml.Vector3d approachLocal;
    private static int ticksLeft;
    private static boolean helmAnchor;
    private static boolean centerAnchor;
    private static int lastJumpStart = -1000;

    static boolean busy() {
        return approaching != null;
    }

    static void cancel(Minecraft mc) {
        if (!busy()) return;
        approaching = null;
        approachLocal = null;
        helmAnchor = false;
        centerAnchor = false;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    /** Owns script execution until ordinary creative-flight input reaches the threshold. */
    static boolean tick(Minecraft mc) {
        if (!busy()) return false;
        try {
            // Touching the deck cancels vanilla creative flight. Reapply the same test flight
            // toggle used to start the approach when crossing the cabin threshold.
            if (mc.player != null && mc.player.isCreative() && mc.player.getAbilities().mayfly
                    && !mc.player.getAbilities().flying)
                ProbeInput.commandFly(mc, "on");
            requireFlight(mc);
            var id = approaching;
            double distance = direction(mc, id).length();
            if (ticksLeft % 200 == 0)
                LOG.info("PROBE ATLAS approach tick {} distance {} remaining {} local {} delta {}",
                        id, distance, ticksLeft, approachLocal,
                        SpaceClient.frame().levelVector(direction(mc, id)));
            if (distance <= stopDistance) {
                cancel(mc);
                LOG.info("PROBE ATLAS approach PASS {} distance {}", id, distance);
            } else if (ticksLeft-- <= 0) {
                throw new IllegalStateException("atlas approach timeout " + id + " distance " + distance);
            } else {
                var d = aim(mc, id);
                double horizontal = Math.hypot(d.x(), d.z());
                // Pulse ordinary keys near a cabin waypoint instead of driving into its lintel
                // at full flight speed. Vertical input also accounts for existing momentum.
                boolean precise = centerAnchor;
                mc.options.keyUp.setDown(!precise || ticksLeft % 10 < Math.ceil(Math.min(1, horizontal / .8) * 10));
                double vertical = precise ? d.y() - mc.player.getDeltaMovement().y * 2.5 : d.y();
                boolean jump = vertical > .03;
                // Two jump rising edges within seven ticks toggle vanilla flight off.
                if (jump && !mc.options.keyJump.isDown()) {
                    if (mc.player.tickCount - lastJumpStart <= 7) jump = false;
                    else lastJumpStart = mc.player.tickCount;
                }
                mc.options.keyJump.setDown(jump);
                mc.options.keyShift.setDown(vertical < -.03);
            }
        } catch (RuntimeException error) {
            cancel(mc);
            ProbeSession.fail(mc, error.getMessage());
        }
        return true;
    }

    static boolean command(Minecraft mc, String arg) {
        if (mc.player == null || mc.level == null)
            throw new IllegalStateException("atlas requires a loaded player and level");
        var parts = arg.trim().split("\\s+", 2);
        String value = parts.length == 2 ? parts[1] : "";
        try {
            switch (parts[0]) {
                case "ships" -> {
                    noArgument(value);
                    var ships = SystemMapApi.ships();
                    LOG.info("PROBE ATLAS ships {} target {}", ships.size(), SystemMapApi.navigationTarget());
                    for (var hull : SubLevelContainer.getContainer(mc.level).getAllSubLevels())
                        LOG.info("PROBE ATLAS live {} pose {} bounds {}", hull.getUniqueId(),
                                hull.logicalPose().position(), hull.getPlot().getBoundingBox());
                    for (var ship : ships)
                        LOG.info("PROBE ATLAS ship {} name {} distance {} body {} face {}",
                                ship.id(), ship.name(), ship.distance(), ship.body(), ship.face());
                }
                case "click" -> click(screen(mc), value);
                case "ship" -> selectShip(screen(mc), UUID.fromString(value));
                case "target" -> {
                    noArgument(value);
                    click(screen(mc), Component.translatable("atlas.starfold_atlas.target").getString());
                    LOG.info("PROBE ATLAS target {}", SystemMapApi.navigationTarget());
                }
                case "require" -> {
                    var expected = UUID.fromString(value);
                    var target = SystemMapApi.navigationTarget();
                    LOG.info("PROBE ATLAS require {} target {}", expected, target);
                    if (!expected.equals(target.ship()))
                        throw new IllegalStateException("atlas expected ship target " + expected + ", actual " + target);
                }
                case "body" -> {
                    int expected = Integer.parseInt(value);
                    var target = SystemMapApi.navigationTarget();
                    LOG.info("PROBE ATLAS body {} target {}", expected, target);
                    if (expected < 0 || target.ship() != null || target.body() != expected)
                        throw new IllegalStateException("atlas expected body target " + expected + ", actual " + target);
                }
                case "cabin" -> {
                    var id = UUID.fromString(value);
                    var body = SubLevelContainer.getContainer(mc.level).getAllSubLevels().stream()
                            .filter(b -> b.getUniqueId().equals(id)).findFirst().orElseThrow();
                    var eye = mc.player.getEyePosition();
                    var feet = mc.player.position();
                    var box = new dev.ryanhcode.sable.companion.math.BoundingBox3d(mc.player.getBoundingBox());
                    box.transformInverse(body.logicalPose(), new org.joml.Matrix4d(), box);
                    LOG.info("PROBE ATLAS cabin {} feet {} eye {} box [{},{},{}]-[{},{},{}] up {} tracking {}",
                            id, body.logicalPose().transformPositionInverse(new org.joml.Vector3d(feet.x, feet.y, feet.z)),
                            body.logicalPose().transformPositionInverse(new org.joml.Vector3d(eye.x, eye.y, eye.z)),
                            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                            body.logicalPose().orientation().transform(new org.joml.Vector3d(0, 1, 0)), tracking(mc));
                    var bounds = body.getPlot().getBoundingBox();
                    for (var at : net.minecraft.core.BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                            bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                        var state = mc.level.getBlockState(at);
                        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                            LOG.info("PROBE ATLAS cabin door {} {}", at.immutable(), state);
                    }
                }
                case "hull" -> hull(mc, value);
                case "useblock" -> useBlock(mc, value);
                case "aim" -> aim(mc, UUID.fromString(value));
                case "approach" -> {
                    var args = value.split("\\s+");
                    if (args.length != 3)
                        throw new IllegalStateException("atlas approach <UUID> <distance> <timeoutTicks>");
                    var id = UUID.fromString(args[0]);
                    double distance = Double.parseDouble(args[1]);
                    int timeout = Integer.parseInt(args[2]);
                    if (!Double.isFinite(distance) || distance <= 0 || timeout <= 0)
                        throw new IllegalStateException("atlas approach requires positive finite distance and timeout");
                    requireFlight(mc);
                    aim(mc, id);
                    approaching = id;
                    stopDistance = distance;
                    ticksLeft = timeout;
                    LOG.info("PROBE ATLAS approach START {} distance {} timeout {}", id, distance, timeout);
                }
                case "approachlocal", "approachhelm", "approachhelmcenter" -> {
                    centerAnchor = parts[0].equals("approachhelmcenter");
                    helmAnchor = !parts[0].equals("approachlocal");
                    var args = value.split("\\s+");
                    if (args.length != 6) throw new IllegalStateException("atlas approachlocal UUID x y z distance timeout");
                    requireFlight(mc);
                    approaching = UUID.fromString(args[0]);
                    approachLocal = new org.joml.Vector3d(Double.parseDouble(args[1]),
                            Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                    stopDistance = Double.parseDouble(args[4]);
                    ticksLeft = Integer.parseInt(args[5]);
                    if (!approachLocal.isFinite() || !Double.isFinite(stopDistance)
                            || stopDistance <= 0 || ticksLeft <= 0)
                        throw new IllegalStateException("atlas approachlocal invalid coordinates or limits");
                }
                case "aboard" -> {
                    var expected = UUID.fromString(value);
                    var actual = tracking(mc);
                    LOG.info("PROBE ATLAS aboard {} tracking {}", expected, actual);
                    if (!expected.equals(actual))
                        throw new IllegalStateException("atlas aboard expected " + expected + ", actual " + actual);
                }
                case "away" -> {
                    var args = value.split("\\s+");
                    if (args.length != 2) throw new IllegalStateException("atlas away <UUID> <min>");
                    var id = UUID.fromString(args[0]);
                    double min = Double.parseDouble(args[1]);
                    if (!Double.isFinite(min) || min < 0)
                        throw new IllegalStateException("atlas away requires finite min >= 0");
                    double distance = ship(id).distance();
                    var actual = tracking(mc);
                    LOG.info("PROBE ATLAS away {} distance {} min {} tracking {}", id, distance, min, actual);
                    if (!Double.isFinite(distance) || distance < min || actual != null)
                        throw new IllegalStateException("atlas away failed " + id);
                }
                case "cargo" -> cargo(mc, value);
                default -> throw new IllegalStateException("atlas expects ships/click/ship/target/require/body/hull/useblock/aim/approach/approachlocal/aboard/away/cargo");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid atlas command: " + arg, e);
        }
        return true;
    }

    private static void useBlock(Minecraft mc, String arg) {
        var args = arg.split("\\s+");
        if (args.length != 1 && args.length != 4)
            throw new IllegalStateException("atlas useblock block [x y z]");
        var block = args[0];
        var position = args.length == 4 ? new net.minecraft.core.BlockPos(Integer.parseInt(args[1]),
                Integer.parseInt(args[2]), Integer.parseInt(args[3])) : null;
        if (mc.screen != null) throw new IllegalStateException("atlas useblock requires no screen");
        // Aim, raycast and click together: render interpolation must not replace the sampled hit.
        for (float pitch = -85; pitch <= 85; pitch += 3)
            for (float yaw = -180; yaw < 180; yaw += 3) {
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                mc.gameRenderer.pick(1);
                if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                        && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && (position == null || hit.getBlockPos().equals(position))
                        && net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock())
                                .toString().equals(block)) {
                    ProbeInput.yaw = yaw;
                    ProbeInput.pitch = pitch;
                    var result = mc.gameMode.useItemOn(mc.player,
                            net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                    LOG.info("PROBE ATLAS useblock {} pos {} result {}", block, hit.getBlockPos(), result);
                    if (!result.consumesAction()) throw new IllegalStateException("atlas useblock rejected " + block);
                    return;
                }
            }
        throw new IllegalStateException("atlas useblock no reachable " + block);
    }

    private static UUID tracking(Minecraft mc) {
        if (HelmClient.body() != null) return HelmClient.body().getUniqueId();
        var body = Sable.HELPER.getTrackingSubLevel(mc.player);
        return body == null ? null : body.getUniqueId();
    }

    private static void requireFlight(Minecraft mc) {
        if (mc.player == null || mc.level == null || !SpaceClient.active()
                || mc.screen != null || HelmClient.body() != null
                || !mc.player.getAbilities().mayfly
                || (!mc.player.getAbilities().flying && !mc.player.onGround()))
            throw new IllegalStateException("atlas approach requires active space, no screen, and creative flight or deck contact");
    }

    private static Point direction(Minecraft mc, UUID id) {
        if (approachLocal != null) {
            var body = SubLevelContainer.getContainer(mc.level).getAllSubLevels().stream()
                    .filter(b -> b.getUniqueId().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("atlas approachlocal hull not loaded"));
            var bounds = body.getPlot().getBoundingBox();
            var local = new org.joml.Vector3d(
                    (bounds.minX() + bounds.maxX() + 1) / 2.0 + approachLocal.x,
                    bounds.minY() + approachLocal.y,
                    (bounds.minZ() + bounds.maxZ() + 1) / 2.0 + approachLocal.z);
            if (helmAnchor) {
                var helms = body.getPlot().getLoadedChunks().stream()
                        .flatMap(h -> h.getChunk().getBlockEntities().values().stream())
                        .filter(be -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock())
                                .toString().equals("starfold:helm"))
                        .map(BlockEntity::getBlockPos).toList();
                if (helms.size() != 1) throw new IllegalStateException("atlas approachhelm requires one helm");
                var helm = helms.getFirst();
                local.set(helm.getX() + approachLocal.x, helm.getY() + approachLocal.y,
                        helm.getZ() + approachLocal.z);
            }
            var at = body.logicalPose().transformPosition(local);
            var origin = centerAnchor ? mc.player.getBoundingBox().getCenter() : mc.player.getEyePosition();
            return SpaceClient.frame().universeVector(new Point(at.x - origin.x,
                    at.y - origin.y, at.z - origin.z));
        }
        if (!SpaceClient.active()) throw new IllegalStateException("atlas aim requires active space");
        var p = ship(id).position();
        var d = new Point(p.x(), p.y(), p.z()).sub(SpaceClient.universeEye(mc.player.getEyePosition()));
        if (!Double.isFinite(d.length())) throw new IllegalStateException("atlas invalid ship position " + id);
        return d;
    }

    private static Point aim(Minecraft mc, UUID id) {
        var d = SpaceClient.frame().levelVector(direction(mc, id));
        ProbeInput.yaw = ProbeInput.pitch = null;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x(), d.z())));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y(), Math.hypot(d.x(), d.z()))));
        mc.gameRenderer.pick(1);
        return d;
    }

    private static void cargo(Minecraft mc, String value) {
        var args = value.split("\\s+", 2);
        if (args.length != 2 || args[1].isBlank())
            throw new IllegalStateException("atlas cargo <UUID> <label>");
        var id = UUID.fromString(args[0]);
        var bodies = SubLevelContainer.getContainer(mc.level);
        if (bodies == null) throw new IllegalStateException("atlas cargo has no client hull container");
        var body = bodies.getAllSubLevels().stream().filter(b -> b.getUniqueId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("atlas cargo client hull missing " + id));
        var containers = body.getPlot().getLoadedChunks().stream()
                .flatMap(holder -> holder.getChunk().getBlockEntities().values().stream())
                .filter(be -> be instanceof Container)
                .sorted(Comparator.comparingInt((BlockEntity be) -> be.getBlockPos().getX())
                        .thenComparingInt(be -> be.getBlockPos().getY())
                        .thenComparingInt(be -> be.getBlockPos().getZ())).toList();
        LOG.info("PROBE ATLAS cargo {} label {} client containers {}", id, args[1], containers.size());
        for (var be : containers) {
            var pos = be.getBlockPos();
            // Container inventories are not synchronized with chunk block-entity data.
            // Read the authoritative inventory through the server's vanilla operator command.
            LOG.info("PROBE ATLAS cargo {} label {} server query pos {} block {}", id, args[1],
                    pos, be.getBlockState());
            mc.player.connection.sendCommand("data get block " + pos.getX() + " "
                    + pos.getY() + " " + pos.getZ() + " Items");
        }
    }

    private static void hull(Minecraft mc, String value) {
        var args = value.split("\\s+");
        if (args.length != 3) throw new IllegalStateException("atlas hull <UUID> <min> <max>");
        var id = UUID.fromString(args[0]);
        double min = Double.parseDouble(args[1]), max = Double.parseDouble(args[2]);
        if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0 || min > max)
            throw new IllegalStateException("atlas hull requires finite 0 <= min <= max");
        var ship = ship(id);
        var tracking = Sable.HELPER.getTrackingSubLevel(mc.player);
        var container = SubLevelContainer.getContainer(mc.level);
        LOG.info("PROBE ATLAS hull {} distance {} min {} max {} tracking {} live {} target {}",
                id, ship.distance(), min, max, tracking == null ? null : tracking.getUniqueId(),
                container == null ? 0 : container.getAllSubLevels().stream().count(), SystemMapApi.navigationTarget());
        if (container == null || container.getAllSubLevels().stream()
                .noneMatch(body -> body.getUniqueId().equals(id)))
            throw new IllegalStateException("atlas client hull missing " + id);
        if (!Double.isFinite(ship.distance()) || ship.distance() < min || ship.distance() > max)
            throw new IllegalStateException("atlas hull " + id + " distance " + ship.distance()
                    + " outside [" + min + ", " + max + "]");
    }

    private static SystemMapApi.Ship ship(UUID id) {
        return SystemMapApi.ships().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("atlas ship missing from API: " + id));
    }

    private static void selectShip(Screen screen, UUID id) {
        var ship = ship(id);
        var ships = SystemMapApi.ships();
        if (ships.stream().filter(s -> s.name().equals(ship.name())).count() != 1)
            throw new IllegalStateException("atlas ship name is ambiguous: " + ship.name());
        // Close any existing selection through Escape before clicking list buttons behind it.
        if (!widgets(screen, Component.translatable("atlas.starfold_atlas.target").getString()).isEmpty())
            screen.keyPressed(256, 0, 0);
        // Traverse through the visible page controls, never invoke focusShip or targeting directly.
        for (int i = 0; i <= ships.size(); i++) {
            if (!widgets(screen, ship.name()).isEmpty()) {
                click(screen, ship.name());
                return;
            }
            if (widgets(screen, "<").isEmpty()) break;
            click(screen, "<");
        }
        for (int i = 0; i <= ships.size(); i++) {
            if (!widgets(screen, ship.name()).isEmpty()) {
                click(screen, ship.name());
                return;
            }
            if (widgets(screen, ">").isEmpty()) break;
            click(screen, ">");
        }
        throw new IllegalStateException("atlas visible ship button missing: " + id + " name " + ship.name());
    }

    private static Screen screen(Minecraft mc) {
        if (mc.screen == null) throw new IllegalStateException("atlas requires an open screen");
        return mc.screen;
    }

    private static List<AbstractWidget> widgets(Screen screen, String caption) {
        return screen.children().stream().filter(child -> child instanceof AbstractWidget)
                .map(child -> (AbstractWidget) child)
                .filter(widget -> widget.visible && widget.active && widget.getMessage().getString().equals(caption))
                .toList();
    }

    private static void click(Screen screen, String caption) {
        var matches = widgets(screen, caption);
        if (caption.isEmpty() || matches.size() != 1)
            throw new IllegalStateException("atlas expected one visible active widget '" + caption + "', found " + matches.size());
        var widget = matches.getFirst();
        double x = widget.getX() + widget.getWidth() / 2.0;
        double y = widget.getY() + widget.getHeight() / 2.0;
        if (x < 0 || x >= screen.width || y < 0 || y >= screen.height)
            throw new IllegalStateException("atlas widget center is outside screen: " + caption);
        boolean accepted;
        try {
            accepted = screen.mouseClicked(x, y, 0);
        } finally {
            screen.mouseReleased(x, y, 0);
        }
        LOG.info("PROBE ATLAS click {} center {},{} accepted {}", caption, x, y, accepted);
        if (!accepted) throw new IllegalStateException("atlas screen rejected click: " + caption);
    }

    private static void noArgument(String value) {
        if (!value.isEmpty()) throw new IllegalStateException("Unexpected atlas argument: " + value);
    }

    private DevAtlasProbe() {}
}
