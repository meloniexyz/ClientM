package com.clientm.module.impl;

import com.clientm.compat.AngelicaBridge;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.IntSetting;

/**
 * Switches on Angelica's HUD caching: the HUD is drawn into an off-screen image a few times a second and that image
 * is reused on every frame in between, instead of redrawing every HUD element every frame.
 */
public class HudCachingModule extends Module {

    private static final String CONFIG = "com.gtnewhorizons.angelica.config.AngelicaConfig";

    private final IntSetting rate = add(new IntSetting("rate", "HUD updates per second", 30, 5, 60));

    public HudCachingModule() {
        super("hudcaching", "HUD Caching", Category.PERFORMANCE, false);
    }

    @Override
    public String description() {
        return AngelicaBridge.available() ? "Off is faster with ClientM's HUD; may help with heavy HUD mods"
            : "Needs Angelica installed";
    }

    @Override
    public void onEnable() {
        apply();
    }

    @Override
    public void onDisable() {
        AngelicaBridge.setStatic(CONFIG, "hudCachingActive", false);
    }

    @Override
    public void onSettingsChanged() {
        if (enabled) apply();
    }

    private void apply() {
        AngelicaBridge.setStatic(CONFIG, "hudCachingFPS", rate.value);
        AngelicaBridge.setStatic(CONFIG, "hudCachingActive", true);
    }
}
