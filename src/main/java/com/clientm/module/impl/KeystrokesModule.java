package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.clientm.module.HudModule;

public class KeystrokesModule extends HudModule {

    private static final int BOX = 18;
    private static final int GAP = 2;
    private static final int WIDE = (BOX * 3 + GAP * 2 - GAP) / 2;
    private static final int TOTAL_W = BOX * 3 + GAP * 2;

    // forward, left, back, right
    private final int[] codes = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
    private final String[] labels = { "W", "A", "S", "D" };
    private final int[] labelWidths = new int[4];
    private int lmbWidth;
    private int rmbWidth;

    public KeystrokesModule() {
        super("keystrokes", "Keystrokes", false, 0.005f, 0.55f);
        background = false;
    }

    @Override
    public void onTick() {
        GameSettings gs = mc.gameSettings;
        refresh(0, gs.keyBindForward);
        refresh(1, gs.keyBindLeft);
        refresh(2, gs.keyBindBack);
        refresh(3, gs.keyBindRight);
        if (lmbWidth == 0) {
            lmbWidth = mc.fontRenderer.getStringWidth("LMB");
            rmbWidth = mc.fontRenderer.getStringWidth("RMB");
        }
    }

    private void refresh(int i, KeyBinding key) {
        int code = key.getKeyCode();
        if (code == codes[i]) return;
        codes[i] = code;
        String n = code < 0 ? Mouse.getButtonName(code + 100) : Keyboard.getKeyName(code);
        if (n == null) n = "?";
        if (n.length() > 3) n = n.substring(0, 3);
        labels[i] = n;
        labelWidths[i] = mc.fontRenderer.getStringWidth(n);
    }

    @Override
    public int width() {
        return TOTAL_W;
    }

    @Override
    public int height() {
        return BOX * 3 + GAP * 3 + 10;
    }

    @Override
    public void render(FontRenderer font) {
        GameSettings gs = mc.gameSettings;
        int step = BOX + GAP;
        box(font, step, 0, BOX, BOX, gs.keyBindForward.getIsKeyPressed(), labels[0], labelWidths[0]);
        box(font, 0, step, BOX, BOX, gs.keyBindLeft.getIsKeyPressed(), labels[1], labelWidths[1]);
        box(font, step, step, BOX, BOX, gs.keyBindBack.getIsKeyPressed(), labels[2], labelWidths[2]);
        box(font, step * 2, step, BOX, BOX, gs.keyBindRight.getIsKeyPressed(), labels[3], labelWidths[3]);
        box(font, 0, step * 2, WIDE, BOX, gs.keyBindAttack.getIsKeyPressed(), "LMB", lmbWidth);
        box(font, WIDE + GAP, step * 2, WIDE, BOX, gs.keyBindUseItem.getIsKeyPressed(), "RMB", rmbWidth);

        boolean jump = gs.keyBindJump.getIsKeyPressed();
        int y = step * 3;
        Gui.drawRect(0, y, TOTAL_W, y + 10, jump ? 0x99FFFFFF : bg());
        Gui.drawRect(TOTAL_W / 2 - 12, y + 4, TOTAL_W / 2 + 12, y + 6, jump ? 0xFF000000 : 0xFFFFFFFF);
    }

    private void box(FontRenderer font, int x, int y, int w, int h, boolean pressed, String label, int labelWidth) {
        Gui.drawRect(x, y, x + w, y + h, pressed ? 0x99FFFFFF : bg());
        font.drawString(label, x + (w - labelWidth) / 2 + 1, y + (h - 8) / 2, pressed ? 0x000000 : 0xFFFFFF);
    }
}
