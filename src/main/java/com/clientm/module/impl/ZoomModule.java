package com.clientm.module.impl;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.IntSetting;

import cpw.mods.fml.client.registry.ClientRegistry;

/**
 * Hold the zoom key (C by default, changeable in Controls) to zoom in; the mouse wheel changes how far while zoomed.
 * Only the field of view changes, the same as OptiFine's zoom.
 */
public class ZoomModule extends Module {

    private static ZoomModule instance;

    private final IntSetting factor = add(new IntSetting("factor", "Zoom", 4, 2, 12));
    private final BoolSetting scroll = add(new BoolSetting("scroll", "Mouse wheel changes the zoom", true));
    private final BoolSetting smooth = add(new BoolSetting("smooth", "Smooth camera while zoomed", true));

    private final KeyBinding key = new KeyBinding("Zoom (ClientM)", Keyboard.KEY_C, "ClientM");

    private boolean zooming;
    private boolean savedSmooth;
    /** The zoom while the key is held; the wheel changes it, and it goes back to the setting on release. */
    private float current;

    public ZoomModule() {
        super("zoom", "Zoom", Category.VISUAL, true);
        instance = this;
        ClientRegistry.registerKeyBinding(key);
    }

    @Override
    public String description() {
        return "Hold C to zoom; scroll to change how far";
    }

    private boolean held() {
        return enabled && mc.currentScreen == null && mc.thePlayer != null && key.getIsKeyPressed();
    }

    /** Called with the field of view the game is about to use (MixinEntityRenderer_Zoom). */
    public static float fov(float fov) {
        ZoomModule m = instance;
        if (m == null) return fov;
        boolean held = m.held();
        if (held != m.zooming) {
            m.zooming = held;
            if (held) {
                m.current = m.factor.value;
                m.savedSmooth = m.mc.gameSettings.smoothCamera;
                if (m.smooth.value) m.mc.gameSettings.smoothCamera = true;
            } else {
                m.mc.gameSettings.smoothCamera = m.savedSmooth;
            }
        }
        return held ? fov / m.current : fov;
    }

    /** For the feature test tour: the zoom key's code. */
    public static int keyCode() {
        return instance == null ? 0 : instance.key.getKeyCode();
    }

    /** Mouse wheel while zoomed: changes the zoom instead of the hotbar slot. True when it was used. */
    public static boolean wheel(int delta) {
        ZoomModule m = instance;
        if (m == null || !m.zooming || !m.scroll.value || delta == 0) return false;
        m.current = Math.max(1.5f, Math.min(30f, m.current * (delta > 0 ? 1.25f : 0.8f)));
        return true;
    }
}
