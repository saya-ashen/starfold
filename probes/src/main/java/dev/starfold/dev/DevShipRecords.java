package dev.starfold.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.starfold.planet.PlanetLevels;
import dev.starfold.ship.ShipParking;
import dev.starfold.ship.ShipRecord;
import dev.starfold.ship.ShipSave;
import dev.starfold.sky.GroundClock;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Lists ship metadata without loading, moving or waking a hull. */
public final class DevShipRecords {
    private DevShipRecords() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("ships")
                .requires(source -> source.hasPermission(2))
                .executes(context -> list(context.getSource()));
    }

    private static int list(CommandSourceStack source) {
        var level = source.getServer().getLevel(PlanetLevels.PLANET);
        if (level == null) {
            source.sendFailure(Component.literal("No system level"));
            return 0;
        }
        var clock = GroundClock.get(level).clock;
        var records = ShipSave.get(level).list();
        var hulls = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (source.getEntity() != null)
            source.sendSuccess(() -> Component.literal("Ship observer level=" + source.getEntity().position()
                    + " motion=" + source.getEntity().getDeltaMovement()), false);
        for (var hull : hulls.getAllSubLevels())
            source.sendSuccess(() -> Component.literal("Live ship " + hull.getUniqueId()
                    + " pose=" + hull.logicalPose().position()), false);
        source.sendSuccess(
                () -> Component.literal("Ships: " + records.size() + " tick=" + clock.tick()),
                false);
        for (var record : records) {
            var saved = dev.starfold.ship.ShipDormancy.read(hulls, record);
            source.sendSuccess(() -> Component.literal("Stored ship " + record.uuid()
                    + " managed=" + dev.starfold.ship.ShipDormancy.managed(level, record.uuid())
                    + " data=" + (saved == null ? "missing" : saved.uuid() + " dependencies=" + saved.dependencies()
                            + " bounds=" + saved.bounds())), false);
            String place;
            if (record.state() instanceof ShipRecord.InSpace space) {
                var p = space.place();
                place =
                        "state=in_space body="
                                + p.body()
                                + " local="
                                + p.position()
                                + " attitude="
                                + p.attitude()
                                + " anchor_plot="
                                + p.anchorPlot()
                                + " captured="
                                + p.tick()
                                + " universe_now="
                                + ShipParking.evaluate(clock.model, p, clock.tick()).position()
                                + " level_now="
                                + ShipParking.toLevel(clock.model, dev.starfold.space.SpaceServer.frame(level), p, clock.tick()).position()
                                + " level_velocity="
                                + ShipParking.toLevel(clock.model, dev.starfold.space.SpaceServer.frame(level), p, clock.tick()).velocity();
            } else {
                var chart = (ShipRecord.OnSlot) record.state();
                int slot = chart.slot();
                place =
                        "state=on_slot slot="
                                + slot
                                + " body="
                                + clock.model.index(slot)
                                + " place=Sable_storage universe_now=not_recorded"
                                + chart.place()
                                        .map(
                                                p ->
                                                        " chart_position="
                                                                + p.position()
                                                                + " attitude="
                                                                + p.attitude()
                                                                + " anchor_plot="
                                                                + p.anchorPlot()
                                                                + " captured="
                                                                + p.tick())
                                        .orElse(" chart_position=not_serialized");
            }
            String line =
                    "id="
                            + record.id().region()
                            + ":"
                            + record.id().index()
                            + " uuid="
                            + record.uuid()
                            + " name="
                            + record.name().orElse("")
                            + " "
                            + place
                            + " locator="
                            + record.locator();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return records.size();
    }
}
