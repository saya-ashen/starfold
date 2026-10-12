package dev.starfold.dev;

import dev.starfold.probe.ArrivalClientState;
import dev.starfold.probe.ArrivalProbe;
import dev.starfold.render.GroundRenderer;
import dev.starfold.render.GroundViewClient;
import dev.starfold.space.SpaceClient;

import net.minecraft.client.Minecraft;

/** Draw-call census, not a claim about camera occlusion or final pixels. */
public final class ArrivalClientProbe {
    private static long previous;

    public static void initialize() {
        ArrivalClientState.census(ArrivalClientProbe::end);
    }

    public static void end(long start) {
        if (!ArrivalProbe.ENABLED) return;
        var mc = Minecraft.getInstance();
        long now = System.nanoTime(), interval = previous == 0 ? 0 : now - previous;
        previous = now;
        if (mc.level == null || mc.player == null) return;
        boolean near = false;
        int x = mc.player.getBlockX() >> 4, z = mc.player.getBlockZ() >> 4;
        for (var section :
                ((dev.starfold.mixin.GroundVisibleSectionsAccess) mc.levelRenderer)
                        .starfold$visibleSections())
            if (section.getOrigin().getX() >> 4 == x
                    && section.getOrigin().getZ() >> 4 == z
                    && !section.getCompiled().hasNoRenderableLayers()) {
                near = true;
                break;
            }
        org.slf4j.LoggerFactory.getLogger("starfold-probe")
                .info(
                        "PROBE ARRIVAL_FRAME wall {} game {} ns {} interval {} region {} remote {} own {} near {} missing {} parents {} coverage {} tiles {} parentNs {}",
                        System.currentTimeMillis(),
                        mc.level.getGameTime(),
                        now - start,
                        interval,
                        SpaceClient.active(),
                        ArrivalClientState.remote,
                        ArrivalClientState.own,
                        near,
                        !mc.level.getChunkSource().hasChunk(x, z),
                        GroundRenderer.probeParents(),
                        GroundViewClient.coverageReady(),
                        GroundRenderer.probeParentTiles(),
                        ArrivalClientState.parentNanos);
    }

    private ArrivalClientProbe() {}
}
