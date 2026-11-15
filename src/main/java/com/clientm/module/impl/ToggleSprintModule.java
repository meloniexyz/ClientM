package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.clientm.module.Category;
import com.clientm.module.HudModule;

/** Tap the sprint key once to keep sprinting, tap again to stop. */
public class ToggleSprintModule extends HudModule {

    private static final String TEXT = "[Sprinting (Toggled)]";

    private boolean toggled;
    private boolean wasDown;
    private boolean forced;
    private int textWidth;

    public ToggleSprintModule() {
        super("togglesprint", "Toggle Sprint", Category.MOVEMENT, true, 0.005f, 0.95f);
        background = false;
    }

    @Override
    public void onTick() {
        if (textWidth == 0) textWidth = mc.fontRenderer.getStringWidth(TEXT);
        int code = mc.gameSettings.keyBindSprint.getKeyCode();
        if (code <= 0) return; // mouse-bound or unbound sprint key: leave vanilla behaviour alone
        boolean down = Keyboard.isKeyDown(code);
        if (down && !wasDown && mc.currentScreen == null) toggled = !toggled;
        wasDown = down;
        if (toggled) {
            KeyBinding.setKeyBindState(code, true);
            forced = true;
        } else if (forced) {
            KeyBinding.setKeyBindState(code, down);
            forced = false;
        }
    }

    @Override
    public void onDisable() {
        toggled = false;
        if (forced) {
            int code = mc.gameSettings.keyBindSprint.getKeyCode();
            if (code > 0) KeyBinding.setKeyBindState(code, Keyboard.isKeyDown(code));
            forced = false;
        }
    }

    @Override
    public int width() {
        return toggled ? textWidth + 6 : 0;
    }

    @Override
    public int height() {
        return toggled ? 14 : 0;
    }

    @Override
    public void render(FontRenderer font) {
        if (toggled) drawLabel(font, TEXT);
    }
}
