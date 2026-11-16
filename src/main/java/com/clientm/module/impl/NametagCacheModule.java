package com.clientm.module.impl;

import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.render.NametagCache;

/** Draws nametags from an image kept per name. The drawing itself is in {@link NametagCache}. */
public class NametagCacheModule extends Module {

    private static NametagCacheModule instance;

    public NametagCacheModule() {
        super("nametagcache", "Nametag Cache", Category.PERFORMANCE, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Nametags drawn from a stored image: less work per player";
    }

    public static boolean active() {
        NametagCacheModule m = instance;
        return m != null && m.enabled;
    }

    @Override
    public void onDisable() {
        NametagCache.clear();
    }
}
