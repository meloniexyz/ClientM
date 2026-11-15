package com.clientm.module.impl;

import java.util.Collection;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.clientm.module.HudModule;

public class PotionStatusModule extends HudModule {

    private static final int MAX = 12;
    private static final String[] LEVEL = { "", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X" };

    private final String[] lines = new String[MAX];
    private final int[] colors = new int[MAX];
    private int count;
    private int maxWidth;
    private int tick;

    public PotionStatusModule() {
        super("potions", "Potion Status", false, 0.005f, 0.3f);
    }

    @Override
    public void onTick() {
        // Durations are shown in seconds, so rebuilding four times a second is plenty.
        if ((tick++ & 3) != 0) return;
        Collection<?> effects = mc.thePlayer.getActivePotionEffects();
        FontRenderer font = mc.fontRenderer;
        count = 0;
        maxWidth = 0;
        for (Object o : effects) {
            if (count == MAX) break;
            PotionEffect e = (PotionEffect) o;
            int amp = e.getAmplifier();
            String level = amp >= 0 && amp < LEVEL.length ? LEVEL[amp] : " " + (amp + 1);
            String line = I18n.format(e.getEffectName()) + level + "  " + Potion.getDurationString(e);
            lines[count] = line;
            colors[count] = e.getDuration() < 200 ? 0xFF5555 : 0xFFFFFF;
            int w = font.getStringWidth(line);
            if (w > maxWidth) maxWidth = w;
            count++;
        }
    }

    @Override
    public int width() {
        return count == 0 ? 0 : maxWidth + 6;
    }

    @Override
    public int height() {
        return count == 0 ? 0 : count * 10 + 4;
    }

    @Override
    public void render(FontRenderer font) {
        if (count == 0) return;
        if (background) Gui.drawRect(0, 0, width(), height(), bg());
        for (int i = 0; i < count; i++) text(font, lines[i], 3, 3 + i * 10, colors[i]);
    }
}
