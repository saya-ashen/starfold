package dev.starfold.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Optional Baritone API bridge for walking between ground checkpoints in the real client. */
final class DevBaritoneProbe {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("starfold-probe");
    private static Object goal, pathing;
    private static BlockPos target;
    private static int remaining;

    static boolean start(Minecraft mc, String arg) {
        if (!mc.player.onGround())
            throw new IllegalStateException("Baritone requires a grounded player");
        var fields = arg.split("\\s+");
        boolean near = fields[0].equals("stair");
        if (near) {
            var foot = DevLandingProbe.loopStairFoot(mc);
            target = BlockPos.containing(foot.x, foot.y + .25, foot.z);
        } else {
            int x = Integer.parseInt(fields[0]),
                    y = Integer.parseInt(fields[1]),
                    z = Integer.parseInt(fields[2]);
            var image = DevTripProbe.chartImage(x + .5, z + .5);
            target = BlockPos.containing(image.x(), y, image.z());
        }
        remaining = Integer.parseInt(fields[near ? 1 : 3]);
        try {
            var baritoneApi = api("BaritoneAPI");
            var settings = baritoneApi.getMethod("getSettings").invoke(null);
            var value = api("Settings$Setting").getField("value");
            for (String name : new String[] {"allowBreak", "allowPlace", "allowSprint"})
                value.set(api("Settings").getField(name).get(settings), false);
            var provider = baritoneApi.getMethod("getProvider").invoke(null);
            var baritone =
                    api("IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
            var client = api("IBaritone");
            pathing = client.getMethod("getPathingBehavior").invoke(baritone);
            goal =
                    near
                            ? api("pathing.goals.GoalNear")
                                    .getConstructor(BlockPos.class, int.class)
                                    .newInstance(target, 2)
                            : api("pathing.goals.GoalBlock")
                                    .getConstructor(BlockPos.class)
                                    .newInstance(target);
            var process = client.getMethod("getCustomGoalProcess").invoke(baritone);
            api("process.ICustomGoalProcess")
                    .getMethod("setGoalAndPath", api("pathing.goals.Goal"))
                    .invoke(process, goal);
            LOG.info(
                    "PROBE BARITONE start args={} target={} from={}",
                    arg,
                    target,
                    mc.player.blockPosition());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot start the Baritone API", error);
        }
        ProbeSession.cursor.line++;
        return false;
    }

    static boolean tick(Minecraft mc) {
        if (goal == null) return false;
        var at = mc.player.blockPosition();
        try {
            if (mc.player.onGround()
                    && Boolean.TRUE.equals(
                            api("pathing.goals.Goal")
                                    .getMethod("isInGoal", int.class, int.class, int.class)
                                    .invoke(goal, at.getX(), at.getY(), at.getZ()))) {
                LOG.info("PROBE BARITONE reached target={} at={}", target, at);
                stop();
                return false;
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot check the Baritone goal", error);
        }
        if (--remaining == 0)
            ProbeSession.fail(mc, "Baritone timeout at " + at + " toward " + target);
        return true;
    }

    static void stop() {
        if (pathing == null) return;
        try {
            api("behavior.IPathingBehavior").getMethod("forceCancel").invoke(pathing);
        } catch (ReflectiveOperationException error) {
            LOG.warn("PROBE BARITONE cancel failed", error);
        } finally {
            goal = pathing = null;
        }
    }

    private static Class<?> api(String name) throws ClassNotFoundException {
        return Class.forName("baritone.api." + name);
    }

    private DevBaritoneProbe() {}
}
