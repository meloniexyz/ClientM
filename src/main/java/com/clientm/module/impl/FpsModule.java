package com.clientm.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import com.clientm.module.HudModule;

public class FpsModule extends HudModule {

    private String text = "0 FPS";
    private int textWidth;
    private int lastFps = -1;

    public FpsModule() {
        super("fps", "FPS", true, 0.005f, 0.01f);
    }

    @Override
    public void onTick() {
        // Minecraft.debugFPS is private in 1.7.10; the debug string starts with "<n> fps".
        int fps = parseLeadingInt(mc.debug);
        if (fps != lastFps) {
            lastFps = fps;
            text = fps + " FPS";
            textWidth = mc.fontRenderer.getStringWidth(text);
        }
    }

    private static int parseLeadingInt(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            n = n * 10 + (c - '0');
        }
        return n;
    }

    @Override
    public int width() {
        return textWidth + 6;
    }

    @Override
    public int height() {
        return Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT + 5;
    }

    @Override
    public void render(FontRenderer font) {
        drawLabel(font, text);
    }
}
