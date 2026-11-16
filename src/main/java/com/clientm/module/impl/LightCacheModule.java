package com.clientm.module.impl;

import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Entities and particles ask the world how bright their block is every frame, but they only move once per game tick.
 * With this on, the answer is looked up once per tick and reused for the frames in between. The hook is in
 * {@code MixinEntity_LightCache}.
 */
public class LightCacheModule extends Module {

    private static LightCacheModule instance;
    /** Never 0, so an entity that has not asked yet never matches. */
    private static int tick = 1;

    public LightCacheModule() {
        super("lightcache", "Entity Light Cache", Category.PERFORMANCE, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Entity and particle brightness looked up once per tick, not every frame";
    }

    @Override
    public void onTick() {
        if (++tick == 0) tick = 1;
    }

    /** The current tick number, or 0 when the cache is off. */
    public static int tick() {
        LightCacheModule m = instance;
        return m != null && m.enabled ? tick : 0;
    }
}
