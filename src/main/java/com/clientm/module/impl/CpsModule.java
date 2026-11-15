package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;

import com.clientm.module.HudModule;

public class CpsModule extends HudModule {

    private final long[] clicks = new long[64];
    private int head;
    private int lastCps = -1;
    private String text = "0 CPS";
    private int textWidth;

    public CpsModule() {
        super("cps", "CPS", false, 0.005f, 0.05f);
    }

    @Override
    public void onMouse(int button, boolean down) {
        if (button != 0 || !down) return;
        clicks[head] = System.currentTimeMillis();
        head = (head + 1) & 63;
    }

    @Override
    public void onTick() {
        long cutoff = System.currentTimeMillis() - 1000L;
        int cps = 0;
        for (long t : clicks) if (t > cutoff) cps++;
        if (cps != lastCps) {
            lastCps = cps;
            text = cps + " CPS";
            textWidth = mc.fontRenderer.getStringWidth(text);
        }
    }

    @Override
    public int width() {
        return textWidth + 6;
    }

    @Override
    public int height() {
        return 14;
    }

    @Override
    public void render(FontRenderer font) {
        drawLabel(font, text);
    }
}
