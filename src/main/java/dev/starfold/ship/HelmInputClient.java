package dev.starfold.ship;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.starfold.network.StarfoldNetwork;
import dev.starfold.probe.ProbeState;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.joml.Vector3d;

/** Recipient-specific pilot authority plus leased hull commands for every tracking client. */
public final class HelmInputClient {
    private record Received(HelmPackets.State state, long tick) {}

    private static final java.util.Map<java.util.UUID, Received> states = new java.util.HashMap<>();

    private record LiftReceived(HelmPackets.Lift state, long tick) {}

    private static final java.util.Map<java.util.UUID, LiftReceived> lifts =
            new java.util.HashMap<>();

    public static HelmPackets.Lift lift(ClientSubLevel body) {
        reset();
        var s = lifts.get(body.getUniqueId());
        var mc = Minecraft.getInstance();
        return s != null && mc.level != null && HelmCommand.fresh(s.tick(), mc.level.getGameTime())
                ? s.state()
                : null;
    }

    private static Object level;
    private static java.util.UUID pilot;
    private static KeyMapping leave;

    public static void initialize(net.neoforged.bus.api.IEventBus bus) {
        leave =
                new KeyMapping(
                        "key.starfold.leave_helm",
                        com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_R,
                        "key.categories.starfold");
        bus.addListener(
                (net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent e) ->
                        e.register(leave));
        StarfoldNetwork.clientReceiver(
                HelmPackets.Lift.TYPE,
                (p, c) -> {
                    reset();
                    var mc = Minecraft.getInstance();
                    if (mc.level != null) {
                        if (p.active())
                            lifts.put(p.body(), new LiftReceived(p, mc.level.getGameTime()));
                        else lifts.remove(p.body());
                    }
                });
        StarfoldNetwork.clientReceiver(
                HelmPackets.State.TYPE,
                (p, c) -> {
                    reset();
                    var mc = Minecraft.getInstance();
                    if (mc.level == null) return;
                    if (p.active()) states.put(p.body(), new Received(p, mc.level.getGameTime()));
                    else states.remove(p.body());
                    if (p.pilot()) pilot = p.body();
                    else if (p.body().equals(pilot)) pilot = null;
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Pre e) -> tick());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.MovementInputUpdateEvent e) -> {
                    if (body() != null) {
                        var input = e.getInput();
                        input.forwardImpulse = 0;
                        input.leftImpulse = 0;
                        input.up = false;
                        input.down = false;
                        input.left = false;
                        input.right = false;
                        input.jumping = false;
                        input.shiftKeyDown = false;
                        e.getEntity().setSprinting(false);
                    }
                });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
                    states.clear();
                    pilot = null;
                    level = null;
                    lifts.clear();
                });
    }

    private static void reset() {
        var mc = Minecraft.getInstance();
        if (level != mc.level) {
            states.clear();
            lifts.clear();
            pilot = null;
            level = mc.level;
        }
    }

    public static HelmPackets.State state(java.util.UUID id) {
        reset();
        var s = states.get(id);
        var mc = Minecraft.getInstance();
        return s != null && mc.level != null && HelmCommand.fresh(s.tick(), mc.level.getGameTime())
                ? s.state()
                : null;
    }

    // Pilot identity is authoritative until an explicit release/disconnect, not a thrust lease.
    // Expiring a command must not restore walking while a held flight key is still down.
    private static HelmPackets.State authority(java.util.UUID id) {
        reset();
        var s = states.get(id);
        return s == null ? null : s.state();
    }

    public static ClientSubLevel body() {
        reset();
        var mc = Minecraft.getInstance();
        if (pilot == null || mc.player == null || mc.level == null || authority(pilot) == null)
            return null;
        var container = SubLevelContainer.getContainer(mc.level);
        if (container != null)
            for (var body : container.getAllSubLevels())
                if (body instanceof ClientSubLevel client
                        && !body.isRemoved()
                        && body.getUniqueId().equals(pilot)) return client;
        return null;
    }

    public static net.minecraft.core.Direction facing(ClientSubLevel body) {
        var s = authority(body.getUniqueId());
        return s == null ? net.minecraft.core.Direction.NORTH : s.facing();
    }

    public static int power(Level level, BlockPos pos, BlockState state) {
        int redstone = state.getValue(BlockStateProperties.POWER);
        if (!(dev.ryanhcode.sable.Sable.HELPER.getContaining(level, pos)
                instanceof ClientSubLevel body)) return redstone;
        var lift = lift(body);
        var nozzle = state.getValue(BlockStateProperties.FACING);
        if (lift != null && nozzle.getAxis() == net.minecraft.core.Direction.Axis.Y)
            return (int)
                    Math.ceil(
                            15
                                    * (nozzle == net.minecraft.core.Direction.DOWN
                                            ? lift.up()
                                            : lift.down()));
        var s = state(body.getUniqueId());
        return HelmCommand.effective(
                redstone,
                s != null
                        && HelmCommand.fires(
                                s.bits(),
                                s.facing(),
                                state.getValue(BlockStateProperties.FACING),
                                new Vector3d(
                                        pos.getX() + .5 - s.cx(),
                                        pos.getY() + .5 - s.cy(),
                                        pos.getZ() + .5 - s.cz()),
                                s.free()));
    }

    static boolean sprint(ClientSubLevel body) {
        var s = state(body.getUniqueId());
        return s != null && (s.bits() & HelmCommand.SPRINT) != 0;
    }

    private static void tick() {
        var body = body();
        if (body == null) {
            while (leave.consumeClick()) {}
            return;
        }
        var mc = Minecraft.getInstance();
        boolean exit = leave.consumeClick();
        while (leave.consumeClick()) {}
        int bits = 0;
        if (!exit && mc.screen == null && (mc.isWindowActive() || ProbeState.active())) {
            var o = mc.options;
            if (o.keyUp.isDown()) bits |= HelmCommand.FORWARD;
            if (o.keyDown.isDown()) bits |= HelmCommand.BACK;
            if (o.keyLeft.isDown()) bits |= HelmCommand.LEFT;
            if (o.keyRight.isDown()) bits |= HelmCommand.RIGHT;
            if (o.keyJump.isDown()) bits |= HelmCommand.UP;
            if (o.keyShift.isDown()) bits |= HelmCommand.DOWN;
            if (o.keySprint.isDown()) bits |= HelmCommand.SPRINT;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new HelmPackets.Input(body.getUniqueId(), bits, exit));
    }

    private HelmInputClient() {}
}
