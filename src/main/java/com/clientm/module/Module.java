package com.clientm.module;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.clientm.setting.Setting;

/** A toggleable client feature. HUD features extend {@link HudModule}. */
public abstract class Module {

    protected static final Minecraft mc = Minecraft.getMinecraft();

    public final String id;
    public final String name;
    public final Category category;
    public final List<Setting> settings = new ArrayList<>();
    public boolean enabled;

    protected Module(String id, String name, Category category, boolean enabledByDefault) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.enabled = enabledByDefault;
    }

    protected <T extends Setting> T add(T setting) {
        settings.add(setting);
        return setting;
    }

    /** Called once per client tick while enabled (or while the HUD editor is open). */
    public void onTick() {}

    /** Called for in-game mouse button changes while enabled. */
    public void onMouse(int button, boolean down) {}

    /** Called when the module is switched on. */
    public void onEnable() {}

    /** Called when the module is switched off, to undo anything it was forcing. */
    public void onDisable() {}

    /** Called after a setting was changed in the menu or loaded from the config. */
    public void onSettingsChanged() {}

    /** False for menu entries that only hold actions and have no on/off state. */
    public boolean toggleable() {
        return true;
    }

    /** One line shown under the module name in the menu, or null. */
    public String description() {
        return null;
    }

    /** 0xRRGGBB colour swatch to show next to the module in the menu, or -1 for none. */
    public int previewColor() {
        return -1;
    }

    public final void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (on) onEnable();
        else onDisable();
    }
}
