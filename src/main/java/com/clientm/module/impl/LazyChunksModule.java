package com.clientm.module.impl;

import com.clientm.compat.AngelicaBridge;
import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Lazy chunk loading: chunk rebuilds always happen on background threads and are picked up when they are ready, so a
 * frame never waits for chunk work. Off means nearby chunks are rebuilt inside the frame, which looks a little more
 * immediate when blocks change but stutters while moving through new terrain.
 *
 * With Angelica this is its "always defer chunk updates" option; without Angelica the module does nothing.
 */
public class LazyChunksModule extends Module {

    public LazyChunksModule() {
        super("lazychunks", "Lazy Chunk Loading", Category.PERFORMANCE, true);
    }

    @Override
    public String description() {
        return AngelicaBridge.available() ? "Frames never wait for chunks" : "Needs Angelica";
    }

    @Override
    public void onEnable() {
        apply(true);
    }

    @Override
    public void onDisable() {
        apply(false);
    }

    @Override
    public void onSettingsChanged() {
        apply(enabled);
    }

    private static void apply(boolean lazy) {
        if (AngelicaBridge.available() && AngelicaBridge.set("performance", "alwaysDeferChunkUpdates", lazy))
            AngelicaBridge.save();
    }
}
