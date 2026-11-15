package com.clientm.module.impl;

import java.util.Collection;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraftforge.client.GuiIngameForge;

import com.clientm.ClientM;
import com.clientm.module.HudModule;
import com.clientm.setting.BoolSetting;

/**
 * The sidebar scoreboard as a HUD module: it can be moved, resized and restyled, and the red score numbers can be
 * hidden. While it is on, the sidebar the game draws itself is switched off. The lines are rebuilt a few times a
 * second and only drawn in between.
 */
public class ScoreboardModule extends HudModule {

    private static final int MAX = 15;
    private static final String[] SAMPLE = { "Kills: 12", "Deaths: 3", "Online: 148", "play.example.net" };

    private final BoolSetting numbers = add(new BoolSetting("numbers", "Show score numbers", false));

    private final String[] names = new String[MAX];
    private final String[] points = new String[MAX];
    private final int[] pointsWidth = new int[MAX];
    private String title = "";
    private int titleWidth;
    private int count;
    private int width;
    private int tick;

    public ScoreboardModule() {
        super("scoreboard", "Scoreboard", false, 0.84f, 0.38f);
    }

    @Override
    public String description() {
        return "Movable sidebar, numbers optional";
    }

    @Override
    public void onDisable() {
        GuiIngameForge.renderObjective = true;
    }

    @Override
    public void onTick() {
        // Also covers the module being loaded as enabled from the config, where onEnable is not called.
        GuiIngameForge.renderObjective = !enabled;
        if ((tick++ & 3) != 0) return;
        FontRenderer font = mc.fontRenderer;
        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = board.func_96539_a(1);
        count = 0;
        if (objective == null) {
            if (!ClientM.modules.editing) {
                width = 0;
                return;
            }
            // Nothing to show here: give the HUD editor something to drag.
            title = "Scoreboard";
            for (int i = 0; i < SAMPLE.length; i++) {
                names[i] = SAMPLE[i];
                points[i] = "§c" + (SAMPLE.length - i);
            }
            count = SAMPLE.length;
        } else {
            title = objective.getDisplayName();
            Collection<?> scores = board.func_96534_i(objective);
            // Sorted lowest first; the sidebar shows the highest on top, and at most 15 lines.
            int skip = Math.max(0, scores.size() - MAX);
            int i = scores.size() - skip;
            count = i;
            for (Object o : scores) {
                if (skip-- > 0) continue;
                Score score = (Score) o;
                i--;
                ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
                names[i] = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                points[i] = "§c" + score.getScorePoints();
            }
        }
        titleWidth = font.getStringWidth(title);
        int w = titleWidth;
        for (int i = 0; i < count; i++) {
            pointsWidth[i] = font.getStringWidth(points[i]);
            int line = font.getStringWidth(names[i]) + (numbers.value ? pointsWidth[i] + 6 : 0);
            if (line > w) w = line;
        }
        width = w + 4;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return width == 0 ? 0 : (count + 1) * 9 + 1;
    }

    @Override
    public void render(FontRenderer font) {
        if (width == 0) return;
        if (background) {
            Gui.drawRect(0, 0, width, 9, bg() + 0x10000000);
            Gui.drawRect(0, 9, width, height(), bg());
        }
        text(font, title, (width - titleWidth) / 2, 1);
        boolean showNumbers = numbers.value;
        for (int i = 0; i < count; i++) {
            int y = 10 + i * 9;
            text(font, names[i], 2, y);
            if (showNumbers) text(font, points[i], width - 2 - pointsWidth[i], y);
        }
    }
}
