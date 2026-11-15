package com.clientm.module.impl;

import com.clientm.compat.AngelicaBridge;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.IntSetting;

/**
 * Chunk loading controls. The thread and render-ahead options drive Angelica's terrain renderer, so they only
 * take effect when Angelica is installed. While this module is off, Angelica's own settings are left untouched.
 */
public class ChunkLoadingModule extends Module {

    private static final int CORES = Math.max(
        1,
        Runtime.getRuntime()
            .availableProcessors());

    private final IntSetting threads = add(new IntSetting("threads", "Builder threads (0 = auto)", 0, 0, CORES));
    private final IntSetting renderAhead = add(new IntSetting("renderAhead", "Render-ahead frames", 3, 0, 9));
    private final BoolSetting applyDistance = add(new BoolSetting("applyDistance", "Control render distance", false));
    private final IntSetting distance = add(new IntSetting("distance", "Render distance (chunks)", 8, 2, 16));

    public ChunkLoadingModule() {
        super("chunks", "Chunk Loading", Category.PERFORMANCE, false);
    }

    @Override
    public String description() {
        return AngelicaBridge.available() ? "Tune how fast and how smoothly chunks load"
            : "Thread and defer options need Angelica installed";
    }

    @Override
    public void onEnable() {
        apply();
    }

    @Override
    public void onSettingsChanged() {
        if (enabled) apply();
    }

    private void apply() {
        boolean reload = false;
        if (AngelicaBridge.available()) {
            // A different thread count only takes effect once the renderer is rebuilt.
            reload = AngelicaBridge.set("performance", "chunkBuilderThreads", threads.value);
            boolean changed = reload;
            changed |= AngelicaBridge.set("performance", "cpuRenderAheadLimit", renderAhead.value);
            if (changed) AngelicaBridge.save();
        }
        if (applyDistance.value && mc.gameSettings.renderDistanceChunks != distance.value) {
            mc.gameSettings.renderDistanceChunks = distance.value;
            mc.gameSettings.saveOptions();
            reload = true;
        }
        if (reload && mc.renderGlobal != null && mc.theWorld != null) mc.renderGlobal.loadRenderers();
    }
}
