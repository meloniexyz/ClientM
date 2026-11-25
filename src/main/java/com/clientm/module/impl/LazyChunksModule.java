package com.clientm.module.impl;

import com.clientm.compat.AngelicaBridge;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.ChoiceSetting;

/**
 * Lazy chunk loading: chunk rebuilds always happen on background threads and are picked up when they are ready, so a
 * frame never waits for chunk work. "Loading speed" then limits how many threads build chunks at once: fewer threads
 * means new terrain appears more slowly but takes less processor time away from the game while moving.
 *
 * With Angelica this drives its "always defer chunk updates" option and its chunk builder thread count; without
 * Angelica the module does nothing.
 */
public class LazyChunksModule extends Module {

    private static final String CONFIG = "com.gtnewhorizons.angelica.config.AngelicaConfig";

    /**
     * Builder threads for each Loading speed choice; 0 lets Angelica pick (half the processor threads). Medium and Slow
     * follow the processor: a fixed 3 builders on a 4-thread processor left the game too little time while terrain
     * loaded, and on a 12+ thread processor 3 is no strain at all.
     */
    private static int threads(int speed) {
        int cpus = Runtime.getRuntime()
            .availableProcessors();
        switch (speed) {
            case 0:
                return 0; // Fast: Angelica's own choice
            case 1:
                return cpus <= 4 ? 1 : cpus <= 8 ? 2 : 3; // Medium
            case 2:
                return cpus <= 8 ? 1 : 2; // Slow
            default:
                return 1; // Slowest
        }
    }

    private final ChoiceSetting speed = add(
        new ChoiceSetting("speed", "Loading speed", 1, "Fast", "Medium", "Slow", "Slowest"));

    public LazyChunksModule() {
        super("lazychunks", "Lazy Chunk Loading", Category.PERFORMANCE, true);
    }

    @Override
    public String description() {
        return AngelicaBridge.available() ? "Frames never wait for chunks" : "Needs Angelica";
    }

    @Override
    public void onEnable() {
        apply();
    }

    @Override
    public void onDisable() {
        apply();
    }

    @Override
    public void onSettingsChanged() {
        apply();
    }

    private void apply() {
        if (!AngelicaBridge.available()) return;
        if (AngelicaBridge.set("performance", "alwaysDeferChunkUpdates", enabled)) AngelicaBridge.save();
        // This static setting is the one the chunk builder really reads; it applies when the renderer is rebuilt.
        int threads = enabled ? threads(speed.value) : 0;
        if (AngelicaBridge.setStatic(CONFIG, "chunkBuilderThreadCount", threads) && mc.theWorld != null
            && mc.renderGlobal != null) mc.renderGlobal.loadRenderers();
    }
}
