package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;

import com.clientm.module.HudModule;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.ChoiceSetting;

public class CpsModule extends HudModule {

    private final ChoiceSetting format = add(new ChoiceSetting("format", "Format", 0, "7 CPS", "CPS: 7", "7"));
    private final BoolSetting right = add(new BoolSetting("right", "Show right click", false));

    private final long[] left = new long[64];
    private final long[] rightClicks = new long[64];
    private int leftHead, rightHead;
    private int lastLeft = -1, lastRight = -1;
    private String text = "0 CPS";
    private int textWidth;

    public CpsModule() {
        super("cps", "CPS", false, 0.005f, 0.05f);
    }

    @Override
    public String description() {
        return "Clicks per second";
    }

    @Override
    public void onSettingsChanged() {
        lastLeft = -1;
    }

    @Override
    public void onMouse(int button, boolean down) {
        if (!down) return;
        if (button == 0) {
            left[leftHead] = System.currentTimeMillis();
            leftHead = (leftHead + 1) & 63;
        } else if (button == 1) {
            rightClicks[rightHead] = System.currentTimeMillis();
            rightHead = (rightHead + 1) & 63;
        }
    }

    @Override
    public void onTick() {
        long cutoff = System.currentTimeMillis() - 1000L;
        int l = 0;
        int r = 0;
        for (long t : left) if (t > cutoff) l++;
        for (long t : rightClicks) if (t > cutoff) r++;
        if (l != lastLeft || r != lastRight) {
            lastLeft = l;
            lastRight = r;
            String n = right.value ? l + " | " + r : Integer.toString(l);
            text = format.value == 0 ? n + " CPS" : format.value == 1 ? "CPS: " + n : n;
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
