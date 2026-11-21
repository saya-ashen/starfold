package dev.starfold.atlas;

import static dev.starfold.atlas.AtlasMapScreen.text;

import dev.starfold.api.MiningMapApi;
import dev.starfold.api.PlanetMapApi;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Read-only snapshot dialog; all navigation still uses the ordinary owner-checked waypoint path.
 */
final class MiningSurveyDetails {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final MiningMapApi.Survey survey;
    private int scroll;
    private int visibleRows = 1;

    MiningSurveyDetails(MiningMapApi.Survey survey) {
        this.survey = survey;
    }

    MiningMapApi.Survey survey() {
        return survey;
    }

    int top(int height) {
        return (height - panelHeight(height)) / 2;
    }

    int panelHeight(int height) {
        return Math.min(270, height - 16);
    }

    int buttonY(int height) {
        return top(height) + panelHeight(height) - 26;
    }

    boolean navigable() {
        return !survey.markerName().isEmpty()
                && PlanetMapApi.markers().stream()
                        .anyMatch(
                                m ->
                                        m.name().equals(survey.markerName())
                                                && m.body() == survey.body()
                                                && m.face() == survey.face()
                                                && m.u() == survey.u()
                                                && m.v() == survey.v());
    }

    void scroll(double steps) {
        scroll =
                Math.max(
                        0,
                        Math.min(
                                Math.max(0, survey.ores().size() - visibleRows),
                                scroll - (int) Math.signum(steps)));
    }

    void render(GuiGraphics g, Font font, int width, int height) {
        int panelWidth = Math.min(380, width - 16), left = (width - panelWidth) / 2;
        int top = top(height), bottom = top + panelHeight(height), x = left + 10, y = top + 9;
        int textWidth = panelWidth - 20;
        g.fill(left, top, left + panelWidth, bottom, 0xf0102430);
        g.drawString(font, text("survey_title"), x, y, 0xffe6a0ff, false);
        y += 14;
        g.drawString(
                font,
                text(
                        "coordinates",
                        text("face." + survey.face()),
                        (int) survey.u(),
                        (int) survey.v()),
                x,
                y,
                0xffaad4dd,
                false);
        y += 12;
        g.drawString(
                font,
                text(
                        "survey_coverage",
                        MiningMapApi.AREA,
                        MiningMapApi.AREA,
                        survey.scannedDepth(),
                        survey.scannedBlocks()),
                x,
                y,
                0xffaad4dd,
                false);
        y += 12;
        String time =
                TIME.format(
                        Instant.ofEpochMilli(survey.completedAt()).atZone(ZoneId.systemDefault()));
        g.drawString(font, text("survey_time", time), x, y, 0xffaad4dd, false);
        y += 12;
        for (var line : font.split(text("survey_stale"), textWidth)) {
            g.drawString(font, line, x, y, 0xffffca70, false);
            y += font.lineHeight;
        }
        y += 5;
        g.drawString(font, text("survey_ores"), x, y, 0xff728e9c, false);
        y += 12;
        var hint =
                font.split(text(navigable() ? "survey_scroll" : "survey_no_waypoint"), textWidth);
        int hintY = buttonY(height) - hint.size() * font.lineHeight - 6;
        int rowsBottom = hintY - 5;
        visibleRows = Math.max(1, (rowsBottom - y) / 12);
        scroll = Math.min(scroll, Math.max(0, survey.ores().size() - visibleRows));
        g.enableScissor(x, y, x + textWidth, Math.max(y, rowsBottom));
        if (survey.ores().isEmpty()) {
            for (var line : font.split(text("survey_empty"), textWidth)) {
                g.drawString(font, line, x, y, 0xffaad4dd, false);
                y += font.lineHeight;
            }
        } else {
            for (int i = scroll; i < Math.min(survey.ores().size(), scroll + visibleRows); i++) {
                var ore = survey.ores().get(i);
                var id = ResourceLocation.parse(ore.blockId());
                Component label =
                        BuiltInRegistries.BLOCK
                                .getOptional(id)
                                .map(block -> block.getName())
                                .orElse(Component.literal(ore.blockId()));
                var counts = text("survey_counts", ore.count(), ore.minDepth(), ore.maxDepth());
                int countsWidth = font.width(counts);
                g.drawString(
                        font,
                        font.plainSubstrByWidth(
                                label.getString(), Math.max(0, textWidth - countsWidth - 8)),
                        x,
                        y,
                        0xffe6d4ed,
                        false);
                g.drawString(font, counts, x + textWidth - countsWidth, y, 0xffaad4dd, false);
                y += 12;
            }
        }
        g.disableScissor();
        for (var line : hint) {
            g.drawString(font, line, x, hintY, 0xff728e9c, false);
            hintY += font.lineHeight;
        }
    }
}
