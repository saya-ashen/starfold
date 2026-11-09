package dev.starfold.space;

import dev.starfold.chart.GasPlayerExposure;
import dev.starfold.chart.GasPlayerExposureState.Participation;

import java.util.ArrayList;
import java.util.List;

/** Pure presentation of the last server observation; no local countdown or damage decisions. */
public final class GasPlayerExposureHud {
    public record Line(String key, List<Object> arguments, int color) {
        public Line {
            arguments = List.copyOf(arguments);
        }
    }

    public static List<Line> lines(GasPlayerExposurePacket packet) {
        if (packet == null || packet.snapshot().isEmpty()) return List.of();
        var snapshot = packet.snapshot().orElseThrow();
        var risk = snapshot.sample().risk();
        if (risk == GasPlayerExposure.Risk.NONE) return List.of();
        int color = switch (risk) {
            case SURVIVAL_LIMIT, NEAR_LIMIT, STRUCTURAL -> 0xffff6655;
            default -> 0xffe9af52;
        };
        String key = switch (risk) {
            case PRESSURE -> "hud.starfold.personal_gas_pressure";
            case STRUCTURAL -> "hud.starfold.personal_gas_structural";
            case NEAR_LIMIT -> "hud.starfold.personal_gas_near_limit";
            case SURVIVAL_LIMIT -> "hud.starfold.personal_gas_survival_limit";
            default -> "hud.starfold.personal_gas_unknown";
        };
        var lines = new ArrayList<Line>(3);
        var arguments = snapshot.sample().reading().isEmpty() ? List.<Object>of()
                : List.<Object>of(Math.round(
                        snapshot.sample().reading().orElseThrow().pressure() * 100));
        lines.add(new Line(key, arguments, color));
        lines.add(new Line("hud.starfold.personal_gas_grace",
                List.of(snapshot.values().graceTicks()), color));
        if (snapshot.participation() != Participation.ACTIVE)
            lines.add(new Line(snapshot.participation() == Participation.EXEMPT
                    ? "hud.starfold.personal_gas_exempt" : "hud.starfold.personal_gas_dead",
                    List.of(), color));
        return List.copyOf(lines);
    }

    private GasPlayerExposureHud() {}
}
