package com.clientm.module.impl;

import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Turns the skies that resource packs bring (their mcpatcher/sky folder) on or off. When off, the normal Minecraft
 * sky is drawn. The hook is in MixinSkyRenderer_Toggle; the sky support itself must be switched on in Angelica's
 * mcpatcherforge.cfg, which the ClientM launcher does.
 */
public class CustomSkiesModule extends Module {

    private static CustomSkiesModule instance;

    public CustomSkiesModule() {
        super("customskies", "Custom Skies", Category.VISUAL, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Skies from resource packs; off for the normal sky";
    }

    public static boolean active() {
        CustomSkiesModule m = instance;
        return m == null || m.enabled;
    }
}
