package dev.starfold.dev;

import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundDaylight;

/** Opt-in, bounded rig observation and explicitly logged stone fixture. No actor or inventory simulation. */
final class DevHaulerProbe {
    static void initialize() {
        if (!Boolean.getBoolean("starfold.haulerProbe")) return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.RegisterCommandsEvent e) -> {
                    e.getDispatcher()
                            .register(
                                    net.minecraft.commands.Commands.literal("haulerrig")
                                            .requires(s -> s.hasPermission(2))
                                            .then(
                                                    net.minecraft.commands.Commands.argument(
                                                                    "label",
                                                                    com.mojang.brigadier.arguments
                                                                            .StringArgumentType
                                                                            .word())
                                                            .executes(
                                                                    c -> {
                                                                        var level =
                                                                                c.getSource()
                                                                                        .getLevel();
                                                                        String label =
                                                                                com.mojang.brigadier
                                                                                        .arguments
                                                                                        .StringArgumentType
                                                                                        .getString(
                                                                                                c,
                                                                                                "label");
                                                                        var log =
                                                                                org.slf4j
                                                                                        .LoggerFactory
                                                                                        .getLogger(
                                                                                                "starfold-probe");
                                                                        var player =
                                                                                c.getSource()
                                                                                        .getPlayerOrException();
                                                                        log.info(
                                                                                "PROBE RIGSUN {} clock {} elevation {}",
                                                                                label,
                                                                                GroundClock.get(
                                                                                                level)
                                                                                        .clock
                                                                                        .tick(),
                                                                                GroundDaylight
                                                                                        .elevation(
                                                                                                level,
                                                                                                player
                                                                                                        .blockPosition()));
                                                                        for (var b :
                                                                                dev.ryanhcode.sable
                                                                                        .api
                                                                                        .sublevel
                                                                                        .SubLevelContainer
                                                                                        .getContainer(
                                                                                                level)
                                                                                        .getAllSubLevels()) {
                                                                            log.info(
                                                                                    "PROBE RIGBODY {} pose {} mass {}",
                                                                                    label,
                                                                                    b.logicalPose(),
                                                                                    b.getMassTracker()
                                                                                            .getMass());
                                                                            for (var h :
                                                                                    b.getPlot()
                                                                                            .getLoadedChunks())
                                                                                for (var be :
                                                                                        h.getChunk()
                                                                                                .getBlockEntities()
                                                                                                .values()) {
                                                                                    String id =
                                                                                            net
                                                                                                    .minecraft
                                                                                                    .core
                                                                                                    .registries
                                                                                                    .BuiltInRegistries
                                                                                                    .BLOCK
                                                                                                    .getKey(
                                                                                                            be.getBlockState()
                                                                                                                    .getBlock())
                                                                                                    .toString();
                                                                                    if (id.contains(
                                                                                                    "drill")
                                                                                            || id
                                                                                                    .contains(
                                                                                                            "auger")
                                                                                            || id
                                                                                                    .contains(
                                                                                                            "engine")
                                                                                            || id
                                                                                                    .contains(
                                                                                                            "clutch")
                                                                                            || id
                                                                                                    .contains(
                                                                                                            "funnel")
                                                                                            || id
                                                                                                    .contains(
                                                                                                            "hopper"))
                                                                                        log.info(
                                                                                                "PROBE RIG {} block {} nbt {}",
                                                                                                label,
                                                                                                be
                                                                                                        .getBlockState(),
                                                                                                be
                                                                                                        .saveWithFullMetadata(
                                                                                                                level
                                                                                                                        .registryAccess()));
                                                                                    if (be
                                                                                            instanceof
                                                                                            net
                                                                                                            .minecraft
                                                                                                            .world
                                                                                                            .level
                                                                                                            .block
                                                                                                            .entity
                                                                                                            .ChestBlockEntity
                                                                                                    chest) {
                                                                                        int cobble =
                                                                                                0;
                                                                                        for (int i =
                                                                                                        0;
                                                                                                i
                                                                                                        < chest
                                                                                                                .getContainerSize();
                                                                                                i++)
                                                                                            if (chest.getItem(
                                                                                                            i)
                                                                                                    .is(
                                                                                                            net
                                                                                                                    .minecraft
                                                                                                                    .world
                                                                                                                    .item
                                                                                                                    .Items
                                                                                                                    .COBBLESTONE))
                                                                                                cobble +=
                                                                                                        chest.getItem(
                                                                                                                        i)
                                                                                                                .getCount();
                                                                                        log.info(
                                                                                                "PROBE RIGCHEST {} pos {} cobblestone {}",
                                                                                                label,
                                                                                                be
                                                                                                        .getBlockPos(),
                                                                                                cobble);
                                                                                    }
                                                                                    if (id.equals(
                                                                                            "create:mechanical_drill")) {
                                                                                        var at =
                                                                                                be
                                                                                                        .getBlockPos();
                                                                                        var v =
                                                                                                b.logicalPose()
                                                                                                        .transformPosition(
                                                                                                                new org
                                                                                                                        .joml
                                                                                                                        .Vector3d(
                                                                                                                        at
                                                                                                                                        .getX()
                                                                                                                                + .5,
                                                                                                                        at
                                                                                                                                        .getY()
                                                                                                                                - .5,
                                                                                                                        at
                                                                                                                                        .getZ()
                                                                                                                                + .5));
                                                                                        var target =
                                                                                                net
                                                                                                        .minecraft
                                                                                                        .core
                                                                                                        .BlockPos
                                                                                                        .containing(
                                                                                                                v.x,
                                                                                                                v.y,
                                                                                                                v.z);
                                                                                        log.info(
                                                                                                "PROBE RIGTARGET {} pos {} state {}",
                                                                                                label,
                                                                                                target,
                                                                                                level
                                                                                                        .getBlockState(
                                                                                                                target));
                                                                                    }
                                                                                }
                                                                        }
                                                                        return 1;
                                                                    })));
                    e.getDispatcher()
                            .register(
                                    net.minecraft.commands.Commands.literal("haulertarget")
                                            .requires(s -> s.hasPermission(2))
                                            .executes(
                                                    c -> {
                                                        var level = c.getSource().getLevel();
                                                        for (var b :
                                                                dev.ryanhcode.sable.api.sublevel
                                                                        .SubLevelContainer
                                                                        .getContainer(level)
                                                                        .getAllSubLevels())
                                                            for (var h :
                                                                    b.getPlot().getLoadedChunks())
                                                                for (var be :
                                                                        java.util.List.copyOf(
                                                                                h.getChunk()
                                                                                        .getBlockEntities()
                                                                                        .values()))
                                                                    if (net.minecraft.core
                                                                            .registries
                                                                            .BuiltInRegistries.BLOCK
                                                                            .getKey(
                                                                                    be.getBlockState()
                                                                                            .getBlock())
                                                                            .toString()
                                                                            .equals(
                                                                                    "create:mechanical_drill")) {
                                                                        var at = be.getBlockPos();
                                                                        var v =
                                                                                b.logicalPose()
                                                                                        .transformPosition(
                                                                                                new org
                                                                                                        .joml
                                                                                                        .Vector3d(
                                                                                                        at
                                                                                                                        .getX()
                                                                                                                + .5,
                                                                                                        at
                                                                                                                        .getY()
                                                                                                                - 1.5,
                                                                                                        at
                                                                                                                        .getZ()
                                                                                                                + .5));
                                                                        var target =
                                                                                net.minecraft.core
                                                                                        .BlockPos
                                                                                        .containing(
                                                                                                v.x,
                                                                                                v.y,
                                                                                                v.z);
                                                                        String command =
                                                                                "setblock "
                                                                                        + target.toShortString()
                                                                                                .replace(
                                                                                                        ",",
                                                                                                        "")
                                                                                        + " minecraft:stone";
                                                                        org.slf4j.LoggerFactory
                                                                                .getLogger(
                                                                                        "starfold-probe")
                                                                                .info(
                                                                                        "PROBE RIG FIXTURE {} old {}",
                                                                                        command,
                                                                                        level
                                                                                                .getBlockState(
                                                                                                        target));
                                                                        level.getServer()
                                                                                .getCommands()
                                                                                .performPrefixedCommand(
                                                                                        c
                                                                                                .getSource(),
                                                                                        command);
                                                                    }
                                                        return 1;
                                                    }));
                });
    }

    private DevHaulerProbe() {}
}
