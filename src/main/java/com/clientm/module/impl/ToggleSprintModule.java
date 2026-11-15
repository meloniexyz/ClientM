package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.clientm.module.Category;
import com.clientm.module.HudModule;
import com.clientm.setting.BoolSetting;

/** Tap the sprint key once to keep sprinting, tap again to stop. With "Always sprint" no key press is needed. */
public class ToggleSprintModule extends HudModule {

    private static final String TOGGLED = "[Sprinting (Toggled)]";
    private static final String ALWAYS = "[Sprinting (Always)]";

    private final BoolSetting always = add(new BoolSetting("always", "Always sprint", false));
    private final BoolSetting showText = add(new BoolSetting("showText", "Show status text", true));

    private boolean toggled;
    private boolean wasDown;
    private boolean forced;
    private String text = TOGGLED;
    private int textWidth;

    public ToggleSprintModule() {
        super("togglesprint", "Toggle Sprint", Category.MOVEMENT, true, 0.005f, 0.95f);
        background = false;
    }

    @Override
    public String description() {
        return "Keep sprinting without holding the key";
    }

    private boolean sprinting() {
        return always.value || toggled;
    }

    @Override
    public void onTick() {
        String wanted = always.value ? ALWAYS : TOGGLED;
        if (textWidth == 0 || wanted != text) {
            text = wanted;
            textWidth = mc.fontRenderer.getStringWidth(text);
        }
        int code = mc.gameSettings.keyBindSprint.getKeyCode();
        if (code <= 0) return; // mouse-bound or unbound sprint key: leave vanilla behaviour alone
        boolean down = Keyboard.isKeyDown(code);
        if (down && !wasDown && mc.currentScreen == null && !always.value) toggled = !toggled;
        wasDown = down;
        if (sprinting()) {
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

    private boolean visible() {
        return showText.value && sprinting();
    }

    @Override
    public int width() {
        return visible() ? textWidth + 6 : 0;
    }

    @Override
    public int height() {
        return visible() ? 14 : 0;
    }

    @Override
    public void render(FontRenderer font) {
        if (visible()) drawLabel(font, text);
    }
}
